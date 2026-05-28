package io.aster.policy.tenant;

import io.aster.policy.security.TrialEndpointGuard;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 租户过滤器，拦截所有请求并验证 X-Tenant-Id header。
 *
 * <p>验证规则：
 * <ul>
 *   <li>缺失 X-Tenant-Id → 400 Bad Request（除豁免路径外）</li>
 *   <li>空字符串或仅空白 → 400 Bad Request</li>
 *   <li>格式不符合规范 → 400 Bad Request</li>
 *   <li>租户不在白名单中（如启用） → 403 Forbidden</li>
 *   <li>有效 tenant ID → 填充 TenantContext</li>
 * </ul>
 *
 * <p>豁免路径（无需 X-Tenant-Id header）：
 * <ul>
 *   <li>/q/* - Quarkus 管理端点（health, metrics, openapi）</li>
 *   <li>/graphql/schema.graphql - GraphQL schema 端点</li>
 * </ul>
 *
 * <p>安全增强：
 * <ul>
 *   <li>租户 ID 格式验证（仅允许字母、数字、连字符、下划线）</li>
 *   <li>可选的租户白名单验证</li>
 *   <li>防止租户 ID 注入攻击</li>
 * </ul>
 */
@Provider
public class TenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * 租户 ID 格式正则表达式
     * 仅允许：字母、数字、连字符、下划线，长度 1-64
     */
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Inject
    TenantContext tenantContext;

    /**
     * 是否启用租户白名单验证
     */
    @ConfigProperty(name = "aster.tenant.whitelist.enabled", defaultValue = "false")
    boolean whitelistEnabled;

    /**
     * 允许的租户 ID 白名单（逗号分隔）
     */
    @ConfigProperty(name = "aster.tenant.whitelist.tenants")
    Optional<String> allowedTenants;

    /**
     * 是否启用严格的租户 ID 格式验证
     */
    @ConfigProperty(name = "aster.tenant.strict-format", defaultValue = "true")
    boolean strictFormat;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // CORS preflight 请求不带自定义 headers，必须放行
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // R28 端到端测试发现：trial 流（匿名访客）不可能持有 X-Tenant-Id。
        // TrialEndpointGuard 已经在 AUTHENTICATION-100 完成三重校验并设置
        // TRIAL_GUARD_PASSED_PROP；这里识别该凭证后用 "trial" 作为伪租户，
        // 与 RoleEnforcementFilter 和 InternalCallerFilter 的 trial 处理对齐。
        //
        // R29 Codex audit：必须复查路径以与 RBAC bypass 保持同一边界。
        // property 单独不足以构成 bypass —— 路径必须正好是 TRIAL_PATH，
        // 防止其它路径误用 property 拿到伪租户。
        if (Boolean.TRUE.equals(
                requestContext.getProperty(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP))
            && TrialEndpointGuard.TRIAL_PATH.equals(
                io.aster.security.PathNormalizer.normalize(path))) {
            tenantContext.setCurrentTenant("trial");
            LOG.debugf("Tenant set to 'trial' for guarded trial request (path=%s)", path);
            return;
        }

        // 豁免路径：管理端点、schema 端点、AI 端点（浏览器直连，无 X-Tenant-Id）
        // /api/internal/* 跨服务接口：tenantId 在路径里或不需要，自带 HMAC 验签
        // /api/v1/lexicons* 语言包查询：登录前/匿名亦需获取可用语言列表
        // 用 slash-boundary 匹配防止误匹配兄弟路径（如 /api/v1/lexicons-admin）
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.equals("/graphql/schema.graphql") || path.equals("graphql/schema.graphql")
                || path.startsWith("/api/v1/ai/") || path.startsWith("api/v1/ai/")
                || matchesLexiconPath(path)
                || path.startsWith("/api/v1/admin/lexicons/") || path.startsWith("api/v1/admin/lexicons/")
                || path.equals("/api/v1/admin/lexicons") || path.equals("api/v1/admin/lexicons")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")) {
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

        // 去除首尾空白
        tenantId = tenantId.trim();

        // 验证租户 ID 格式（防止注入攻击）
        if (strictFormat && !TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            LOG.warnf("Invalid tenant ID format: %s (must match pattern: %s)",
                sanitizeForLog(tenantId), TENANT_ID_PATTERN.pattern());
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 characters).")
                    .build()
            );
            return;
        }

        // 兼容旧版：宽松模式下仅验证长度
        if (!strictFormat && tenantId.length() > 255) {
            LOG.warnf("Tenant ID too long: %d characters", tenantId.length());
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("Tenant ID must not exceed 255 characters")
                    .build()
            );
            return;
        }

        // 可选：验证租户是否在白名单中
        if (whitelistEnabled && !isTenantAllowed(tenantId)) {
            LOG.warnf("Tenant not in whitelist: %s", sanitizeForLog(tenantId));
            requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .entity("Tenant not authorized")
                    .build()
            );
            return;
        }

        // 设置租户上下文
        tenantContext.setCurrentTenant(tenantId);
        LOG.debugf("Tenant context initialized: %s for path: %s", tenantId, path);
    }

    /**
     * 检查租户是否在白名单中
     */
    private boolean isTenantAllowed(String tenantId) {
        if (!whitelistEnabled || allowedTenants.isEmpty()) {
            return true;
        }

        Set<String> allowed = Set.of(allowedTenants.get().split(","));
        return allowed.contains(tenantId);
    }

    /**
     * 清理日志输出（防止日志注入）
     */
    private String sanitizeForLog(String input) {
        if (input == null) {
            return "null";
        }
        // 截断过长的输入，移除控制字符
        String sanitized = input.length() > 100 ? input.substring(0, 100) + "..." : input;
        return sanitized.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * Slash-boundary 路径匹配，避免 startsWith("/api/v1/lexicons") 误匹配
     * 兄弟路径如 /api/v1/lexicons-admin。
     */
    private boolean matchesLexiconPath(String path) {
        if ("/api/v1/lexicons".equals(path) || "api/v1/lexicons".equals(path)) return true;
        return path.startsWith("/api/v1/lexicons/") || path.startsWith("api/v1/lexicons/");
    }
}
