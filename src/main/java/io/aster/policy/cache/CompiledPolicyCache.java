package io.aster.policy.cache;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编译策略缓存
 *
 * L1 内存缓存，避免重复编译策略。
 * 使用 ConcurrentHashMap 实现线程安全。
 */
@ApplicationScoped
public class CompiledPolicyCache {

    private static final Logger LOG = Logger.getLogger(CompiledPolicyCache.class);

    private final ConcurrentHashMap<String, CompiledPolicy> cache = new ConcurrentHashMap<>();

    /**
     * 获取缓存的编译策略
     */
    public Optional<CompiledPolicy> get(String tenantId, String module, String function, String versionId) {
        String key = buildKey(tenantId, module, function, versionId);
        CompiledPolicy policy = cache.get(key);

        if (policy != null) {
            LOG.debugf("编译缓存命中: %s", key);
        }

        return Optional.ofNullable(policy);
    }

    /**
     * 缓存编译策略
     */
    public void put(String tenantId, String module, String function, CompiledPolicy policy) {
        String key = buildKey(tenantId, module, function, policy.getVersionId());
        cache.put(key, policy);
        LOG.infof("编译策略已缓存: %s", key);
    }

    /**
     * 失效指定策略的所有版本缓存
     */
    public void invalidate(String tenantId, String module, String function) {
        String prefix = buildPrefix(tenantId, module, function);
        int removed = 0;

        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removed++;
            }
        }

        if (removed > 0) {
            LOG.infof("失效编译缓存: %s, 移除 %d 个版本", prefix, removed);
        }
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        LOG.infof("清空编译缓存，共 %d 个策略", size);
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        return new CacheStats(cache.size());
    }

    private String buildKey(String tenantId, String module, String function, String versionId) {
        return String.format("%s:%s:%s:%s", tenantId, module, function, versionId);
    }

    private String buildPrefix(String tenantId, String module, String function) {
        return String.format("%s:%s:%s:", tenantId, module, function);
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(int size) {
        @Override
        public String toString() {
            return "CacheStats{size=" + size + '}';
        }
    }
}
