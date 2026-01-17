package io.aster.policy.api.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.metrics.PolicyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.pubsub.PubSubCommands.RedisSubscriber;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 策略缓存管理器，封装生命周期跟踪与租户索引。
 */
@ApplicationScoped
public class PolicyCacheManager {

    private static final Logger LOG = Logger.getLogger(PolicyCacheManager.class);
    private static final String INVALIDATION_CHANNEL = "policy-cache:invalidate";
    private static final String CACHE_NAME = "policy-results";

    @Inject
    @CacheName("policy-results")
    Cache policyResultCache;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    PolicyMetrics policyMetrics;

    private CaffeineCache caffeineCacheDelegate;

    private com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, Boolean> cacheLifecycleTracker;

    private final ConcurrentHashMap<String, Set<PolicyCacheKey>> tenantCacheIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheHitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheMissCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheEvictionCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> remoteInvalidationCounters = new ConcurrentHashMap<>();
    private PubSubCommands<String> pubSubCommands;
    private RedisSubscriber redisSubscriber;
    private ExecutorService invalidationExecutor;

    @PostConstruct
    void initCacheLifecycleTracking() {
        Duration expireAfterWrite = null;
        Long maximumSize = null;
        Integer initialCapacity = null;

        try {
            caffeineCacheDelegate = policyResultCache.as(CaffeineCache.class);
            if (caffeineCacheDelegate instanceof io.quarkus.cache.runtime.caffeine.CaffeineCacheImpl impl) {
                var info = impl.getCacheInfo();
                initialCapacity = info.initialCapacity;
                maximumSize = info.maximumSize;
                expireAfterWrite = info.expireAfterWrite;
            }
        } catch (IllegalStateException ex) {
            LOG.warn("政策评估缓存未使用Caffeine后端，租户索引将使用默认监听配置", ex);
            caffeineCacheDelegate = null;
        }

        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (initialCapacity != null) {
            builder.initialCapacity(initialCapacity);
        }
        if (maximumSize != null) {
            builder.maximumSize(maximumSize);
        }
        if (expireAfterWrite != null) {
            builder.expireAfterWrite(expireAfterWrite);
        }

        cacheLifecycleTracker = builder.removalListener((PolicyCacheKey removedKey, Boolean ignored, RemovalCause cause) -> {
            if (removedKey != null) {
                recordEviction(removedKey);
                removeTrackedKey(removedKey);
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("Caffeine移除缓存键, cause=%s, key=%s", cause, removedKey);
                }
            }
        }).build();

        registerCacheMetrics();
        initRedisInvalidationChannel();
    }

    public Cache getPolicyResultCache() {
        return policyResultCache;
    }

    public CaffeineCache getCaffeineCacheDelegate() {
        return caffeineCacheDelegate;
    }

    public com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, Boolean> getLifecycleTracker() {
        return cacheLifecycleTracker;
    }

    public com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> getNativePolicyCache() {
        if (caffeineCacheDelegate == null) {
            return null;
        }
        try {
            var getCache = caffeineCacheDelegate.getClass().getDeclaredMethod("getCache");
            getCache.setAccessible(true);
            Object cache = getCache.invoke(caffeineCacheDelegate);
            if (cache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
                @SuppressWarnings("unchecked")
                com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> typed =
                    (com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?>) caffeineCache;
                return typed;
            }
        } catch (NoSuchMethodException ignored) {
            // ignore and try field scan
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("通过反射获取底层Caffeine缓存失败", e);
            }
        }
        try {
            for (var field : caffeineCacheDelegate.getClass().getDeclaredFields()) {
                if (com.github.benmanes.caffeine.cache.Cache.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(caffeineCacheDelegate);
                    if (value instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
                        @SuppressWarnings("unchecked")
                        com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?> typed =
                            (com.github.benmanes.caffeine.cache.Cache<PolicyCacheKey, ?>) caffeineCache;
                        return typed;
                    }
                }
            }
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("通过字段访问底层Caffeine缓存失败", e);
            }
        }
        return null;
    }

    private void initRedisInvalidationChannel() {
        if (redisDataSource == null || redisDataSource.isUnsatisfied()) {
            LOG.debug("RedisDataSource 未配置，跳过分布式缓存广播");
            return;
        }
        try {
            invalidationExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "policy-cache-invalidation");
                thread.setDaemon(true);
                return thread;
            });
            pubSubCommands = redisDataSource.get().pubsub(String.class);
            redisSubscriber = pubSubCommands.subscribe(INVALIDATION_CHANNEL, payload ->
                invalidationExecutor.execute(() -> handleRemoteInvalidation(payload))
            );
            LOG.infof("Redis 分布式缓存失效通道已启用: %s", INVALIDATION_CHANNEL);
        } catch (Exception e) {
            LOG.warn("初始化 Redis 缓存失效订阅失败", e);
        }
    }

    public void registerCacheEntry(PolicyCacheKey key, String tenantId) {
        if (key == null) {
            return;
        }
        String normalizedTenant = normalizeTenant(tenantId != null ? tenantId : key.getTenantId());
        tenantCacheIndex
            .computeIfAbsent(normalizedTenant, k -> ConcurrentHashMap.newKeySet())
            .add(key);
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.put(key, Boolean.TRUE);
        }
    }

    public void removeCacheEntry(PolicyCacheKey key, String tenantId) {
        if (key == null) {
            return;
        }
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.invalidate(key);
        }
        removeTrackedKey(key, tenantId);
        publishInvalidation(key);
    }

    public boolean isCacheHit(PolicyCacheKey key) {
        if (key == null) {
            return false;
        }
        String normalizedTenant = normalizeTenant(key.getTenantId());
        Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
        boolean hit = keys != null && keys.contains(key);
        recordCacheLookup(normalizedTenant, hit);
        return hit;
    }

    public Set<PolicyCacheKey> snapshotTenantCacheKeys(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(keys));
    }

    public void invalidateTenantCache(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        Set<PolicyCacheKey> keys = tenantCacheIndex.remove(normalizedTenant);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (cacheLifecycleTracker != null) {
            for (PolicyCacheKey key : keys) {
                cacheLifecycleTracker.invalidate(key);
            }
        }
        publishInvalidation(normalizedTenant, null, null, null);
    }

    public void clearAllCache() {
        tenantCacheIndex.clear();
        if (cacheLifecycleTracker != null) {
            cacheLifecycleTracker.invalidateAll();
        }
    }

    private void removeTrackedKey(PolicyCacheKey cacheKey) {
        removeTrackedKey(cacheKey, cacheKey != null ? cacheKey.getTenantId() : null);
    }

    private void removeTrackedKey(PolicyCacheKey cacheKey, String tenantId) {
        if (cacheKey == null) {
            return;
        }
        String normalizedTenant = normalizeTenant(tenantId != null ? tenantId : cacheKey.getTenantId());
        Set<PolicyCacheKey> keys = tenantCacheIndex.get(normalizedTenant);
        if (keys != null) {
            keys.remove(cacheKey);
            if (keys.isEmpty()) {
                tenantCacheIndex.remove(normalizedTenant, keys);
            }
        }
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    // 包内可见，便于集成测试直接触发远程失效逻辑
    void handleRemoteInvalidation(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject message = new JsonObject(payload);
            String tenantId = normalizeTenant(message.getString("tenantId"));
            String policyModule = message.getString("policyModule");
            String policyFunction = message.getString("policyFunction");
            Integer hash = message.containsKey("hash") ? message.getInteger("hash") : null;

            recordRemoteInvalidation(tenantId);

            if (policyModule == null && policyFunction == null && hash == null) {
                tenantCacheIndex.remove(tenantId);
                return;
            }

            Set<PolicyCacheKey> keys = tenantCacheIndex.get(tenantId);
            if (keys == null || keys.isEmpty()) {
                return;
            }
            keys.stream()
                .filter(k -> matches(policyModule, k.getPolicyModule()))
                .filter(k -> matches(policyFunction, k.getPolicyFunction()))
                .filter(k -> hash == null || k.hashCode() == hash)
                .forEach(k -> removeTrackedKey(k, tenantId));
        } catch (Exception e) {
            LOG.warnf(e, "解析 Redis 缓存失效消息失败: %s", payload);
        }
    }

    private boolean matches(String pattern, String value) {
        if (pattern == null) {
            return true;
        }
        return pattern.equals(value);
    }

    private void publishInvalidation(PolicyCacheKey key) {
        if (key == null) {
            return;
        }
        publishInvalidation(key.getTenantId(), key.getPolicyModule(), key.getPolicyFunction(), key.hashCode());
    }

    private void publishInvalidation(String tenantId, String module, String function, Integer hash) {
        if (pubSubCommands == null || tenantId == null) {
            return;
        }
        try {
            JsonObject json = new JsonObject()
                .put("tenantId", normalizeTenant(tenantId))
                .put("policyModule", module)
                .put("policyFunction", function);
            if (hash != null) {
                json.put("hash", hash);
            }
            pubSubCommands.publish(INVALIDATION_CHANNEL, json.encode());
        } catch (Exception e) {
            LOG.debug("广播缓存失效消息失败", e);
        }
    }

    @PreDestroy
    void shutdownInvalidationChannel() {
        if (redisSubscriber != null) {
            try {
                redisSubscriber.unsubscribe();
            } catch (Exception ignored) {
            }
        }
        if (invalidationExecutor != null) {
            invalidationExecutor.shutdownNow();
        }
    }

    private void registerCacheMetrics() {
        if (meterRegistry == null) {
            return;
        }
        // 缓存容量 Gauge：实时反映 cacheLifecycleTracker 估算的条目数
        Gauge.builder("policy_cache_size", () -> cacheLifecycleTracker != null ? cacheLifecycleTracker.estimatedSize() : 0)
            .description("策略缓存当前条目数量")
            .tag("cache_name", CACHE_NAME)
            .register(meterRegistry);

        // 活跃租户 Gauge：跟踪维护索引的租户个数
        Gauge.builder("policy_cache_active_tenants", tenantCacheIndex, ConcurrentHashMap::size)
            .description("策略缓存活跃租户数量")
            .tag("cache_name", CACHE_NAME)
            .register(meterRegistry);

        // 预注册默认租户计数器，避免冷启动首次命中/未命中时阻塞
        String defaultTenant = normalizeTenant(null);
        cacheHitCounters.computeIfAbsent(defaultTenant, this::createHitCounter);
        cacheMissCounters.computeIfAbsent(defaultTenant, this::createMissCounter);
        cacheEvictionCounters.computeIfAbsent(defaultTenant, this::createEvictionCounter);
        remoteInvalidationCounters.computeIfAbsent(defaultTenant, this::createRemoteInvalidationCounter);
    }

    private void recordCacheLookup(String tenant, boolean hit) {
        if (meterRegistry == null || tenant == null) {
            return;
        }
        if (hit) {
            incrementCounter(cacheHitCounters, tenant, this::createHitCounter);
            if (policyMetrics != null) {
                policyMetrics.recordCacheHit();
            }
        } else {
            incrementCounter(cacheMissCounters, tenant, this::createMissCounter);
            if (policyMetrics != null) {
                policyMetrics.recordCacheMiss();
            }
        }
    }

    private void recordEviction(PolicyCacheKey removedKey) {
        if (meterRegistry == null || removedKey == null) {
            return;
        }
        incrementCounter(cacheEvictionCounters, normalizeTenant(removedKey.getTenantId()), this::createEvictionCounter);
    }

    private void recordRemoteInvalidation(String tenant) {
        if (meterRegistry == null || tenant == null) {
            return;
        }
        incrementCounter(remoteInvalidationCounters, tenant, this::createRemoteInvalidationCounter);
    }

    private void incrementCounter(ConcurrentHashMap<String, Counter> store, String tenant, Function<String, Counter> factory) {
        store.computeIfAbsent(tenant, factory).increment();
    }

    private Counter createHitCounter(String tenant) {
        return Counter.builder("policy_cache_hits_total")
            .description("策略缓存命中次数统计")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createMissCounter(String tenant) {
        return Counter.builder("policy_cache_misses_total")
            .description("策略缓存未命中次数统计")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createEvictionCounter(String tenant) {
        return Counter.builder("policy_cache_evictions_total")
            .description("策略缓存移除事件次数")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }

    private Counter createRemoteInvalidationCounter(String tenant) {
        return Counter.builder("policy_cache_remote_invalidations_total")
            .description("策略缓存远程失效次数")
            .tag("cache_name", CACHE_NAME)
            .tag("tenant", tenant)
            .register(meterRegistry);
    }
}
