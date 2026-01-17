package io.aster.policy.api;

import io.aster.policy.api.cache.PolicyCacheManager;
import io.aster.policy.api.PolicyCacheKey;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.benmanes.caffeine.cache.Cache;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheManagementService 单元测试，覆盖缓存清理、统计封装与失效逻辑。
 */
@ExtendWith(MockitoExtension.class)
class CacheManagementServiceTest {

    private CacheManagementService cacheManagementService;

    @Mock
    private PolicyEvaluationService policyEvaluationService;

    @Mock
    private PolicyCacheManager policyCacheManager;

    @BeforeEach
    void setUp() {
        cacheManagementService = new CacheManagementService(policyEvaluationService, policyCacheManager);
    }

    @Test
    void testClearAllCache_Success() {
        // Given
        when(policyEvaluationService.clearAllCache())
            .thenReturn(Uni.createFrom().voidItem());

        // When
        Uni<CacheManagementService.CacheOperationResult> result = cacheManagementService.clearAllCache();

        // Then
        CacheManagementService.CacheOperationResult opResult = result
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(opResult.success).isTrue();
        assertThat(opResult.message).contains("cleared successfully");
        assertThat(opResult.timestamp).isPositive();
    }

    @Test
    void testInvalidateTenantCache_AllEntries() {
        // Given
        when(policyEvaluationService.invalidateCache(anyString(), isNull(), isNull()))
            .thenAnswer(invocation -> Uni.createFrom().voidItem());

        // When
        Uni<CacheManagementService.CacheOperationResult> result =
            cacheManagementService.invalidateTenantCache("tenant-a");

        // Then
        CacheManagementService.CacheOperationResult opResult = result
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(opResult.success).isTrue();
        assertThat(opResult.message).contains("tenant tenant-a");
        assertThat(opResult.message).contains("all entries");
    }

    @Test
    void testInvalidateTenantCache_WithModuleAndFunction() {
        // Given
        when(policyEvaluationService.invalidateCache("tenant-a", "finance.loan", "evaluateLoan"))
            .thenReturn(Uni.createFrom().voidItem());

        // When
        Uni<CacheManagementService.CacheOperationResult> result =
            cacheManagementService.invalidateTenantCache("tenant-a", "finance.loan", "evaluateLoan");

        // Then
        CacheManagementService.CacheOperationResult opResult = result
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(opResult.success).isTrue();
        assertThat(opResult.message).contains("tenant-a");
        assertThat(opResult.message).contains("finance.loan.evaluateLoan");
    }

    @Test
    void testInvalidateTenantCache_WithModuleOnly() {
        // Given
        when(policyEvaluationService.invalidateCache("tenant-b", "finance.creditcard", null))
            .thenReturn(Uni.createFrom().voidItem());

        // When
        Uni<CacheManagementService.CacheOperationResult> result =
            cacheManagementService.invalidateTenantCache("tenant-b", "finance.creditcard", null);

        // Then
        CacheManagementService.CacheOperationResult opResult = result
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(opResult.success).isTrue();
        assertThat(opResult.message).contains("tenant-b");
        assertThat(opResult.message).contains("module finance.creditcard");
    }

    @Test
    void testGetCacheStatistics_WithLifecycleTracker() {
        // Given
        @SuppressWarnings("unchecked")
        Cache<PolicyCacheKey, Boolean> mockLifecycleTracker = org.mockito.Mockito.mock(Cache.class);
        PolicyCacheKey mockKey = new PolicyCacheKey("tenant-a", "aster.finance.loan", "evaluateLoan", new Object[]{123456});
        java.util.concurrent.ConcurrentMap<PolicyCacheKey, Boolean> mockMap = new java.util.concurrent.ConcurrentHashMap<>();
        mockMap.put(mockKey, true);

        when(policyCacheManager.getLifecycleTracker()).thenReturn(mockLifecycleTracker);
        when(mockLifecycleTracker.estimatedSize()).thenReturn(5L);
        when(mockLifecycleTracker.asMap()).thenReturn(mockMap);
        when(policyCacheManager.getNativePolicyCache()).thenReturn(null);
        when(policyCacheManager.getCaffeineCacheDelegate()).thenReturn(null);

        // When
        CacheManagementService.CacheStatistics stats = cacheManagementService.getCacheStatistics();

        // Then
        assertThat(stats.trackedEntries).isEqualTo(5L);
        assertThat(stats.estimatedSize).isEqualTo(5L);
        assertThat(stats.tenantCount).isEqualTo(1L);
        assertThat(stats.caffeineBacked).isFalse();
    }

    @Test
    void testGetCacheStatistics_NoCacheAvailable() {
        // Given
        when(policyCacheManager.getLifecycleTracker()).thenReturn(null);
        when(policyCacheManager.getNativePolicyCache()).thenReturn(null);
        when(policyCacheManager.getCaffeineCacheDelegate()).thenReturn(null);

        // When
        CacheManagementService.CacheStatistics stats = cacheManagementService.getCacheStatistics();

        // Then
        assertThat(stats.trackedEntries).isZero();
        assertThat(stats.estimatedSize).isZero();
        assertThat(stats.tenantCount).isZero();
        assertThat(stats.caffeineBacked).isFalse();
    }

    @Test
    void testInvalidateTenantCache_FailurePropagates() {
        // Given
        when(policyEvaluationService.invalidateCache("tenant-a", "finance.loan", "evaluateLoan"))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("invalidate failed")));

        // When
        UniAssertSubscriber<CacheManagementService.CacheOperationResult> subscriber =
            cacheManagementService.invalidateTenantCache("tenant-a", "finance.loan", "evaluateLoan")
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("invalidate failed");
    }

    @Test
    void testConcurrentInvalidateTenantCache() throws Exception {
        // Given
        when(policyEvaluationService.invalidateCache(anyString(), isNull(), isNull()))
            .thenReturn(Uni.createFrom().voidItem());

        int concurrentTasks = 12;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentTasks);

        IntStream.range(0, concurrentTasks).forEach(i -> {
            final int finalI = i;
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await();
                    cacheManagementService.invalidateTenantCache("tenant-" + finalI)
                        .subscribe().withSubscriber(UniAssertSubscriber.<CacheManagementService.CacheOperationResult>create())
                        .awaitItem()
                        .getItem();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        });

        // When
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        // Then
        assertThat(completed).isTrue();
        verify(policyEvaluationService, times(concurrentTasks)).invalidateCache(anyString(), isNull(), isNull());
    }

    @Test
    void testInvalidateTenantCache_NotFoundGraceful() {
        // Given
        when(policyEvaluationService.invalidateCache("tenant-a", "module.missing", "op"))
            .thenReturn(Uni.createFrom().voidItem());

        // When
        CacheManagementService.CacheOperationResult result = cacheManagementService
            .invalidateTenantCache("tenant-a", "module.missing", "op")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        // Then
        assertThat(result.success).isTrue();
        assertThat(result.message).contains("module.missing.op");
    }
}
