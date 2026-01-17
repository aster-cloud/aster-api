package io.aster.policy.tenant;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * 租户过滤器，拦截所有请求并验证 X-Tenant-Id header。
 *
 * <p>规则：
 * <ul>
 *   <li>缺失 X-Tenant-Id → 400 Bad Request（除豁免路径外）</li>
 *   <li>空字符串或仅空白 → 400 Bad Request（除豁免路径外）</li>
 *   <li>有效 tenant ID → 填充 TenantContext</li>
 * </ul>
 *
 * <p>豁免路径（无需 X-Tenant-Id header）：
 * <ul>
 *   <li>/q/* - Quarkus 管理端点（health, metrics, openapi）</li>
 *   <li>/graphql/schema.graphql - GraphQL schema 端点</li>
 * </ul>
 */
@Provider
public class TenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Inject
    TenantContext tenantContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();

        // 豁免路径：仅管理端点和 schema 端点
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.equals("/graphql/schema.graphql") || path.equals("graphql/schema.graphql")) {
            LOG.debugf("Bypassing tenant validation for path: %s", path);
            // 豁免路径使用默认租户
            tenantContext.setCurrentTenant("default");
            return;
        }

        String tenantId = requestContext.getHeaderString(TENANT_HEADER);

        // 严格校验：缺失或空白 header 返回 400
        if (tenantId == null || tenantId.trim().isEmpty()) {
            LOG.warnf("Missing or empty %s header for path: %s", TENANT_HEADER, path);
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(String.format("Missing or empty %s header", TENANT_HEADER))
                    .build()
            );
            return;
        }

        // 验证 tenant ID 格式（可选：添加更严格的验证）
        if (tenantId.length() > 255) {
            LOG.warnf("Tenant ID too long: %d characters", tenantId.length());
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Tenant ID must not exceed 255 characters")
                    .build()
            );
            return;
        }

        // 设置租户上下文
        tenantContext.setCurrentTenant(tenantId);
        LOG.debugf("Tenant context initialized: %s for path: %s", tenantId, path);
    }
}
