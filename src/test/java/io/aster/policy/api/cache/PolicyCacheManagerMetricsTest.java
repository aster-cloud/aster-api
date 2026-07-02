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

    /**
     * 安全审计 C3 回归：远程（跨 pod）失效必须真正失效 Quarkus @CacheName("policy-results")
     * **主结果缓存**（policyResultCache），而不仅仅清 tenantCacheIndex 追踪表 / cacheLifecycleTracker。
     *
     * <p>历史缺陷：{@code handleRemoteInvalidation → removeTrackedKey} 只删追踪索引，不失效主
     * 结果缓存，导致跨 pod 发版/回滚后本 pod 的主缓存条目留到 15m TTL（stale reads）。
     * 本测试用 valueLoader 探测**真实主缓存**是否被驱逐：失效后再 get 应触发 loader 重载
     * （返回新值），而非命中旧值——这是 C3 修复前后的行为差异（Codex 复审指出测错对象后修正）。
     */
    @Test
    void remoteInvalidationMustEvictRealPolicyResultCache() {
        String tenant = "tenant-c3";
        var key = new PolicyCacheKey(tenant, "aster.audit.c3", "evaluate", new Object[]{"ctx-c3"});
        cacheManager.registerCacheEntry(key, tenant);

        io.quarkus.cache.Cache resultCache = cacheManager.getPolicyResultCache();
        // 向**真实主缓存**放入初值（valueLoader 首次调用）。
        String first = resultCache.<PolicyCacheKey, String>get(key, k -> "STALE").await().indefinitely();
        assertTrue("STALE".equals(first), "前置：主缓存应缓存初值 STALE");

        JsonObject payload = new JsonObject()
            .put("tenantId", tenant)
            .put("policyModule", key.getPolicyModule())
            .put("policyFunction", key.getPolicyFunction())
            .put("hash", key.hashCode());
        cacheManager.handleRemoteInvalidation(payload.encode());

        // C3 核心断言：主缓存该 key 已被真正驱逐 → 再 get 触发 loader 重载返回新值。
        String reloaded = resultCache.<PolicyCacheKey, String>get(key, k -> "RELOADED").await().indefinitely();
        assertTrue("RELOADED".equals(reloaded),
            "C3 回归：远程失效必须驱逐真实 policy-results 主缓存条目（否则仍命中 STALE = 跨 pod stale read）");
        assertFalse(cacheManager.isCacheHit(key), "追踪索引也应清除");
    }

    /** C3：全租户远程失效同样须驱逐真实主缓存里该租户所有条目。 */
    @Test
    void remoteFullTenantInvalidationMustEvictRealPolicyResultCache() {
        String tenant = "tenant-c3-full";
        var key = new PolicyCacheKey(tenant, "aster.audit.c3full", "evaluate", new Object[]{"ctx"});
        cacheManager.registerCacheEntry(key, tenant);

        io.quarkus.cache.Cache resultCache = cacheManager.getPolicyResultCache();
        String first = resultCache.<PolicyCacheKey, String>get(key, k -> "STALE").await().indefinitely();
        assertTrue("STALE".equals(first), "前置：主缓存应缓存初值 STALE");

        // 全租户失效 payload（无 module/function/hash）
        JsonObject payload = new JsonObject().put("tenantId", tenant);
        cacheManager.handleRemoteInvalidation(payload.encode());

        String reloaded = resultCache.<PolicyCacheKey, String>get(key, k -> "RELOADED").await().indefinitely();
        assertTrue("RELOADED".equals(reloaded),
            "C3 回归：全租户远程失效须驱逐真实主缓存该租户所有条目");
        assertFalse(cacheManager.isCacheHit(key));
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
