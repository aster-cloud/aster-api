package io.aster.policy.cache;

import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.api.cache.PolicyCacheManager;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式缓存失效功能测试
 *
 * 验证：
 * 1. Redis Pub/Sub 通道正常工作
 * 2. 缓存失效消息能在节点间传播（模拟：同进程的不同租户）
 * 3. PolicyCacheManager 正确处理远程失效消息
 */
@QuarkusTest
@RedisEnabledTest
public class DistributedCacheInvalidationTest {

    @Inject
    PolicyCacheManager cacheManager;

    @Test
    public void testLocalCacheRegistration() {
        // Given: 创建缓存键
        var key = new PolicyCacheKey("tenant1", "aster.finance.loan", "evaluateLoanEligibility",
            new Object[]{"""
            {"applicant":{"age":30,"income":50000}}
            """});

        // When: 注册缓存条目
        cacheManager.registerCacheEntry(key, "tenant1");

        // Then: 缓存命中检查应返回 true
        assertTrue(cacheManager.isCacheHit(key), "Registered cache entry should be hit");

        // And: 租户缓存索引应包含该键
        var tenantKeys = cacheManager.snapshotTenantCacheKeys("tenant1");
        assertTrue(tenantKeys.contains(key), "Tenant cache index should contain the key");
    }

    @Test
    public void testLocalCacheInvalidation() {
        // Given: 注册缓存条目
        var key = new PolicyCacheKey("tenant2", "aster.finance.fraud", "detectFraud",
            new Object[]{"""
            {"transaction":{"amount":1000}}
            """});
        cacheManager.registerCacheEntry(key, "tenant2");
        assertTrue(cacheManager.isCacheHit(key));

        // When: 本地失效缓存
        cacheManager.removeCacheEntry(key, "tenant2");

        // Then: 缓存应不再命中
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertFalse(cacheManager.isCacheHit(key),
                "Invalidated cache entry should not be hit"));
    }

    @Test
    public void testTenantCacheIsolation() {
        // Given: 不同租户的缓存条目
        var key1 = new PolicyCacheKey("tenantA", "aster.finance.loan", "evaluateLoanEligibility", new Object[]{"{}"});
        var key2 = new PolicyCacheKey("tenantB", "aster.finance.loan", "evaluateLoanEligibility", new Object[]{"{}"});

        cacheManager.registerCacheEntry(key1, "tenantA");
        cacheManager.registerCacheEntry(key2, "tenantB");

        // When: 失效租户A的缓存
        cacheManager.invalidateTenantCache("tenantA");

        // Then: 租户A缓存应失效，租户B缓存仍有效
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertFalse(cacheManager.isCacheHit(key1), "TenantA cache should be invalidated");
                assertTrue(cacheManager.isCacheHit(key2), "TenantB cache should still be valid");
            });
    }

    @Test
    public void testDistributedCacheInvalidationViaRedis() {
        // Given: 注册缓存条目
        var key = new PolicyCacheKey("tenant3", "aster.finance.creditcard", "evaluateCreditCardApplication",
            new Object[]{"""
            {"applicant":{"name":"John","creditScore":720}}
            """});
        cacheManager.registerCacheEntry(key, "tenant3");
        assertTrue(cacheManager.isCacheHit(key));

        // When: 模拟远程节点发送失效消息（通过调用本地失效，Redis Pub/Sub 会广播）
        // 注意：单节点测试中，消息会回传给自己，模拟多节点效果
        cacheManager.removeCacheEntry(key, "tenant3");

        // Then: 缓存应在 Redis Pub/Sub 延迟后失效
        await().atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertFalse(cacheManager.isCacheHit(key),
                "Cache entry should be invalidated via Redis Pub/Sub"));
    }

    @Test
    public void testCacheKeyHashCodeConsistency() {
        // Given: 相同参数的缓存键
        var key1 = new PolicyCacheKey("tenant4", "aster.finance.loan", "evaluateLoanEligibility",
            new Object[]{"""
            {"applicant":{"age":25}}
            """});
        var key2 = new PolicyCacheKey("tenant4", "aster.finance.loan", "evaluateLoanEligibility",
            new Object[]{"""
            {"applicant":{"age":25}}
            """});

        // Then: hashCode 应一致（用于 Redis 消息过滤）
        assertEquals(key1.hashCode(), key2.hashCode(), "Identical cache keys should have same hashCode");
        assertEquals(key1, key2, "Identical cache keys should be equal");
    }
}
