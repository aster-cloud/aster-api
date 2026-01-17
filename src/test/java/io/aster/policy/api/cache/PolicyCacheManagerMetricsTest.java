package io.aster.policy.api.cache;

import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.api.cache.PolicyCacheManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 PolicyCacheManager Micrometer 指标是否正确累计。
 */
@QuarkusTest
public class PolicyCacheManagerMetricsTest {

    @Inject
    PolicyCacheManager cacheManager;

    @Inject
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        cacheManager.clearAllCache();
    }

    @Test
    void shouldRecordHitsAndMissesPerTenant() {
        String tenant = "tenant-metrics";
        var key = new PolicyCacheKey(tenant, "aster.module", "functionA", new Object[]{"ctx"});

        double hitBefore = counterValue("policy_cache_hits_total", tenant);
        double missBefore = counterValue("policy_cache_misses_total", tenant);

        assertFalse(cacheManager.isCacheHit(key), "未注册前应判定为未命中");

        cacheManager.registerCacheEntry(key, tenant);
        assertTrue(cacheManager.isCacheHit(key), "注册后应返回命中");

        double hitAfter = counterValue("policy_cache_hits_total", tenant);
        double missAfter = counterValue("policy_cache_misses_total", tenant);

        assertEquals(hitBefore + 1, hitAfter, 0.0001, "命中计数应累计一次");
        assertEquals(missBefore + 1, missAfter, 0.0001, "未命中计数应累计一次");
    }

    @Test
    void shouldTrackEvictionsAndGauges() {
        String tenant = "tenant-evict";
        var key = new PolicyCacheKey(tenant, "aster.module", "functionB", new Object[]{"ctx"});

        double evictionBefore = counterValue("policy_cache_evictions_total", tenant);

        cacheManager.registerCacheEntry(key, tenant);
        double cacheSizeDuring = gaugeValue("policy_cache_size");
        double activeTenantDuring = gaugeValue("policy_cache_active_tenants");
        assertTrue(cacheSizeDuring >= 1.0, "缓存 Gauge 应随注册增大");
        assertTrue(activeTenantDuring >= 1.0, "活跃租户 Gauge 应>=1");

        cacheManager.removeCacheEntry(key, tenant);

        double evictionAfter = counterValue("policy_cache_evictions_total", tenant);
        double cacheSizeAfter = gaugeValue("policy_cache_size");
        double activeTenantAfter = gaugeValue("policy_cache_active_tenants");

        assertEquals(evictionBefore + 1, evictionAfter, 0.0001, "驱逐计数应累计一次");
        assertTrue(cacheSizeAfter <= 0.001, "移除后缓存 Gauge 应恢复为0");
        assertTrue(activeTenantAfter <= 0.001, "活跃租户 Gauge 应恢复为0");
    }

    @Test
    void shouldCountRemoteInvalidations() {
        String tenant = "tenant-remote";
        var key = new PolicyCacheKey(tenant, "aster.module", "functionC", new Object[]{"ctx"});
        cacheManager.registerCacheEntry(key, tenant);
        assertTrue(cacheManager.isCacheHit(key), "注册后需保证存在缓存键以测试远程失效");

        double invalidationBefore = counterValue("policy_cache_remote_invalidations_total", tenant);

        JsonObject payload = new JsonObject()
            .put("tenantId", tenant)
            .put("policyModule", key.getPolicyModule())
            .put("policyFunction", key.getPolicyFunction())
            .put("hash", key.hashCode());

        cacheManager.handleRemoteInvalidation(payload.encode());

        double invalidationAfter = counterValue("policy_cache_remote_invalidations_total", tenant);
        assertEquals(invalidationBefore + 1, invalidationAfter, 0.0001, "远程失效计数应累计一次");
        assertFalse(cacheManager.isCacheHit(key), "远程失效后键应被移除");
    }

    private double counterValue(String meterName, String tenant) {
        var counter = meterRegistry.find(meterName)
            .tag("cache_name", "policy-results")
            .tag("tenant", tenant)
            .counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double gaugeValue(String meterName) {
        var gauge = meterRegistry.find(meterName)
            .tag("cache_name", "policy-results")
            .gauge();
        return gauge != null ? gauge.value() : 0.0;
    }
}
