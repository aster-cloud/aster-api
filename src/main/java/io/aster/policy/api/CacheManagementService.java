package io.aster.policy.api;

import io.aster.policy.api.cache.PolicyCacheManager;
import io.aster.policy.api.PolicyCacheKey;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 缓存管理服务，封装缓存清理与统计能力。
 */
@ApplicationScoped
public class CacheManagementService {

    private static final Logger LOG = Logger.getLogger(CacheManagementService.class);

    @Inject
    PolicyEvaluationService policyEvaluationService;

    @Inject
    PolicyCacheManager policyCacheManager;

    // 无参构造器供CDI使用
    public CacheManagementService() {
    }

    // 包私有构造器供测试使用
    CacheManagementService(PolicyEvaluationService policyEvaluationService, PolicyCacheManager policyCacheManager) {
        this.policyEvaluationService = policyEvaluationService;
        this.policyCacheManager = policyCacheManager;
    }

    /**
     * 清空所有缓存并返回操作结果。
     */
    public Uni<CacheOperationResult> clearAllCache() {
        long timestamp = System.currentTimeMillis();
        return policyEvaluationService.clearAllCache()
            .onItem().transform(v -> new CacheOperationResult(
                Boolean.TRUE,
                "All policy cache cleared successfully",
                timestamp
            ));
    }

    /**
     * 使指定租户的缓存失效，允许同时按模块与函数过滤。
     */
    public Uni<CacheOperationResult> invalidateTenantCache(String tenantId, String policyModule, String policyFunction) {
        String normalizedTenant = normalizeTenant(tenantId);
        String normalizedModule = sanitize(policyModule);
        String normalizedFunction = sanitize(policyFunction);
        long timestamp = System.currentTimeMillis();
        return policyEvaluationService.invalidateCache(normalizedTenant, normalizedModule, normalizedFunction)
            .onItem().transform(v -> new CacheOperationResult(
                Boolean.TRUE,
                buildInvalidateMessage(normalizedTenant, normalizedModule, normalizedFunction),
                timestamp
            ));
    }

    /**
     * 快捷方法：按租户整体失效缓存。
     */
    public Uni<CacheOperationResult> invalidateTenantCache(String tenantId) {
        return invalidateTenantCache(tenantId, null, null);
    }

    /**
     * 获取当前缓存统计数据。
     */
    public CacheStatistics getCacheStatistics() {
        CacheStatistics stats = new CacheStatistics();
        var lifecycleTracker = policyCacheManager.getLifecycleTracker();
        if (lifecycleTracker != null) {
            stats.trackedEntries = lifecycleTracker.estimatedSize();
            Set<String> tenants = lifecycleTracker.asMap().keySet().stream()
                .filter(Objects::nonNull)
                .map(PolicyCacheKey::getTenantId)
                .filter(Objects::nonNull)
                .map(this::normalizeTenant)
                .collect(Collectors.toSet());
            stats.tenantCount = (long) tenants.size();
        } else {
            stats.trackedEntries = 0L;
            stats.tenantCount = 0L;
        }

        var nativeCache = policyCacheManager.getNativePolicyCache();
        if (nativeCache != null) {
            stats.estimatedSize = nativeCache.estimatedSize();
        } else if (lifecycleTracker != null) {
            stats.estimatedSize = lifecycleTracker.estimatedSize();
        } else {
            stats.estimatedSize = 0L;
        }

        stats.caffeineBacked = policyCacheManager.getCaffeineCacheDelegate() != null;
        return stats;
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    private String buildInvalidateMessage(String tenant, String module, String function) {
        if (module == null && function == null) {
            return "Cache invalidated for tenant " + tenant + " (all entries)";
        }
        if (module != null && function == null) {
            return "Cache invalidated for tenant " + tenant + " module " + module;
        }
        if (module == null) {
            return "Cache invalidated for tenant " + tenant + " function " + function;
        }
        return "Cache invalidated for tenant " + tenant + " policy " + module + "." + function;
    }

    /**
     * 缓存操作结果。
     */
    public static class CacheOperationResult {
        @Description("操作是否成功 / Operation success status")
        public Boolean success;

        @Description("操作结果消息 / Operation message")
        public String message;

        @Description("操作时间戳 / Operation timestamp")
        public Long timestamp;

        public CacheOperationResult() {
            // GraphQL 序列化需要无参构造函数
        }

        public CacheOperationResult(Boolean success, String message, Long timestamp) {
            this.success = success;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    /**
     * 缓存统计数据。
     */
    public static class CacheStatistics {
        @Description("跟踪中的缓存条目数 / Tracked cache entries")
        public Long trackedEntries;

        @Description("缓存的估算大小 / Estimated cache size")
        public Long estimatedSize;

        @Description("存在缓存的租户数量 / Active tenant count")
        public Long tenantCount;

        @Description("是否启用了Caffeine后端 / Whether caffeine backend is active")
        public Boolean caffeineBacked;
    }
}

