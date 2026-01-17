package io.aster.policy.tenant;

import jakarta.enterprise.context.RequestScoped;

/**
 * 请求范围的租户上下文，用于在单个请求生命周期内传播租户信息。
 *
 * 由 TenantFilter 在请求开始时填充，供业务层使用。
 */
@RequestScoped
public class TenantContext {

    private String tenantId;

    /**
     * 获取当前请求的租户ID
     *
     * @return 租户ID，如果未设置则抛出异常
     * @throws IllegalStateException 如果租户上下文未初始化
     */
    public String getCurrentTenant() {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context not initialized for current request");
        }
        return tenantId;
    }

    /**
     * 设置当前请求的租户ID
     *
     * @param tenantId 租户ID
     */
    public void setCurrentTenant(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * 检查租户上下文是否已初始化
     *
     * @return true 如果已设置租户ID
     */
    public boolean isInitialized() {
        return tenantId != null;
    }
}
