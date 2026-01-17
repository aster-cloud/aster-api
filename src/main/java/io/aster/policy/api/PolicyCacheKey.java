package io.aster.policy.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Cache key for policy evaluation results
 *
 * 该类用于生成缓存键，基于策略模块、函数名、版本信息和输入参数。
 * 实现了equals和hashCode以确保缓存正确工作。
 * 包含版本信息确保热更新后不会读到旧缓存。
 */
public class PolicyCacheKey {
    private final String tenantId;
    private final String policyModule;
    private final String policyFunction;
    private final String versionId;  // 新增：策略版本标识
    private final Object[] context;
    private final int hashCode;

    /**
     * 构造缓存键（带版本信息）
     */
    public PolicyCacheKey(String tenantId, String policyModule, String policyFunction, String versionId, Object[] context) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.versionId = versionId;
        this.context = context;
        // 预计算哈希码以提高性能
        this.hashCode = computeHashCode();
    }

    /**
     * 构造缓存键（向后兼容，不带版本信息）
     */
    public PolicyCacheKey(String tenantId, String policyModule, String policyFunction, Object[] context) {
        this(tenantId, policyModule, policyFunction, null, context);
    }

    private int computeHashCode() {
        int result = Objects.hash(tenantId, policyModule, policyFunction, versionId);
        result = 31 * result + Arrays.deepHashCode(context);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyCacheKey that = (PolicyCacheKey) o;
        return Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(policyModule, that.policyModule) &&
               Objects.equals(policyFunction, that.policyFunction) &&
               Objects.equals(versionId, that.versionId) &&
               Arrays.deepEquals(context, that.context);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "PolicyCacheKey{" +
                "tenantId='" + tenantId + '\'' +
                ", policyModule='" + policyModule + '\'' +
                ", policyFunction='" + policyFunction + '\'' +
                ", versionId='" + versionId + '\'' +
                ", contextSize=" + (context != null ? context.length : 0) +
                '}';
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPolicyModule() {
        return policyModule;
    }

    public String getPolicyFunction() {
        return policyFunction;
    }

    public String getVersionId() {
        return versionId;
    }

    public Object[] getContext() {
        return context;
    }
}
