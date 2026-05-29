package io.aster.security.apikey;

import io.aster.security.apikey.ApiKeyVerifyResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Map;

/**
 * API key 认证过滤器
 *
 * 仅作用于公开生产路径：/api/v1/policies/evaluate{,-json,/batch}
 * 不作用于：/api/internal/*（内部 HMAC）、/api/v1/policies/evaluate-source（仅 BFF）、
 *          /api/v1/policies（其它 CRUD 由 cloud 处理）、/q/*（健康检查）
 *
 * 行为：
 *   - 缺少 Authorization Bearer → 401
 *   - 格式不对（不以 ak_ 开头）→ 401
 *   - 验证失败（not_found / revoked / expired）→ 401（响应体含 reason）
 *   - 验证通过 → 把 userId/tenantId/apiKeyId 放进 RoutingContext
 *     供下游 ApiQuotaGuard.check() / recordApiCall() 使用
 *
 * Priority 比 RequestSignatureFilter (AUTHENTICATION) 略低，确保 HMAC 先验。
 */
@ApplicationScoped
public class ApiKeyAuthFilter {

    private static final Logger LOG = Logger.getLogger(ApiKeyAuthFilter.class);

    @Inject
    ApiKeyVerifierService verifier;

    @ConfigProperty(name = "aster.security.apikey.enabled", defaultValue = "true")
    boolean enabled;

    // R32：filter 在 event loop 上运行。底层 Redis getApiKey 抛
    // "cannot be blocked" 是因为 LocalQuotaSnapshotService 用了同步
    // ValueCommands<String>。修复在 snapshot service 层用 try/catch
    // 包住 IllegalStateException，让 redis 路径 fall through 到 cloud
    // verify 而不是抛到 filter（fail-closed → 401）。
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 100)
    public void filter(ContainerRequestContext ctx) {
        if (!enabled) return;

        String path = ctx.getUriInfo().getPath();
        if (!shouldProtect(path)) {
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw unauthorized("missing_authorization");
        }
        String key = auth.substring(7).trim();
        if (key.isEmpty()) {
            throw unauthorized("empty_token");
        }

        ApiKeyVerifyResult result = verifier.verify(key);
        if (!result.valid()) {
            LOG.infof("apikey verify rejected: path=%s reason=%s", path, result.reason());
            throw unauthorized(result.reason() != null ? result.reason() : "invalid_key");
        }

        // 写入 request context，下游 PolicyEvaluationResource 可读
        // tenantId / userId / apiKeyId 一并 propagate
        ctx.setProperty("aster.apikey.userId", result.userId());
        ctx.setProperty("aster.apikey.tenantId", result.tenantId());
        ctx.setProperty("aster.apikey.apiKeyId", result.apiKeyId());

        // 兜底：如果客户端没传 X-Tenant-Id / X-User-Id / X-Api-Key-Id，自动补齐
        if (ctx.getHeaderString("X-Tenant-Id") == null) {
            ctx.getHeaders().putSingle("X-Tenant-Id", result.tenantId());
        }
        if (ctx.getHeaderString("X-User-Id") == null) {
            ctx.getHeaders().putSingle("X-User-Id", result.userId());
        }
        if (ctx.getHeaderString("X-Api-Key-Id") == null) {
            ctx.getHeaders().putSingle("X-Api-Key-Id", result.apiKeyId());
        }
        // R32 hotfix：RoleEnforcementFilter @RequireRole(MEMBER) 要求 X-User-Role
        // 头。aster-cloud BFF 调用时会显式带上；直接拿 API key 的 raw curl
        // 调用没有这个头会被 403 "Missing role information"。
        // API key 是 user 自己生成的，本质上代表 user → 默认给 MEMBER
        // 角色（足够执行 evaluate / read，不能做 admin mutate）。
        if (ctx.getHeaderString("X-User-Role") == null) {
            ctx.getHeaders().putSingle("X-User-Role", "MEMBER");
        }
    }

    /**
     * 仅保护公开生产路径。
     *
     * <p>R21-Critical-1 背景：生产 k3s 部署把全局 {@code aster.security.signature.enabled}
     * 关掉了（理由：浏览器 Monaco/AI/lexicon 直连无法签名），但这意味着
     * "策略版本管理" 类端点（rollback / cache-clear / version 列表）会失去全局签名
     * 兜底而完全敞开。这些端点是租户内部突变操作，必须 API key 守护。
     *
     * <p>分层（含此 filter 覆盖范围）：
     * <ul>
     *   <li>{@code /evaluate*} —— public 计算端点：API key 必填</li>
     *   <li>{@code /evaluate-source} —— internal SSE：InternalCallerFilter 守护</li>
     *   <li>{@code /{policyId}/rollback} —— 突变：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /{policyId}/versions} —— 历史查询：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /cache} —— 缓存清理：R21-Critical-1 起 API key 必填</li>
     *   <li>{@code /schema}、{@code /validate} —— 元数据查询：保持匿名（无副作用）</li>
     * </ul>
     */
    private static boolean shouldProtect(String path) {
        // R23-Critical-1 + R25-Critical-1：matrix-param 归一化必须 **per-segment**。
        // R23 的 "在第一个 ; 截断" 把 /foo;x/rollback → /foo（丢失 /rollback）→ 绕过。
        // R25 改用 PathNormalizer 按 / 切段、每段独立 strip ;... 后拼回，与 RESTEasy
        // 路由层一致。
        String p = io.aster.security.PathNormalizer.normalize(path);

        if (!p.startsWith("/api/v1/policies/")) return false;
        // 公开 evaluate 端点（按调用量计费）
        // 显式排除 evaluate-source（由 InternalCallerFilter 守护）
        if (p.equals("/api/v1/policies/evaluate")) return true;
        if (p.equals("/api/v1/policies/evaluate-json")) return true;
        if (p.equals("/api/v1/policies/evaluate/batch")) return true;
        // R21-Critical-1：cache 清理 (DELETE /cache) —— 突变操作必须鉴权
        if (p.equals("/api/v1/policies/cache")) return true;
        // R21-Critical-1：版本管理 —— rollback / versions 列表必须鉴权
        // path 形如 /api/v1/policies/{policyId}/rollback 或 /{policyId}/versions
        if (p.endsWith("/rollback")) return true;
        if (p.endsWith("/versions")) return true;
        return false;
    }

    private static WebApplicationException unauthorized(String reason) {
        return new WebApplicationException(
            Response.status(401)
                .entity(Map.of(
                    "error", "unauthorized",
                    "reason", reason,
                    "message", "Invalid or revoked API key. See https://aster-lang.cloud/billing/api-keys"
                ))
                .type(MediaType.APPLICATION_JSON)
                .build()
        );
    }
}
