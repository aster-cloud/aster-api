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
    /**
     * R32 hotfix v3: 返回 {@code Uni<Response>}（null = continue chain）。
     * RESTEasy Reactive 看到 Uni 就不会阻塞 event-loop 等结果，而是 subscribe
     * 后让出线程；verify 在 {@link ApiKeyVerifierService#verifyPool} 上跑完
     * 再触发下游 emission。Cache 命中走 fast path 同步返回，不付 Uni 调度开销。
     *
     * @return null/Uni.item(null) = 继续；Uni.item(Response) = 401 短路
     */
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 100)
    public io.smallrye.mutiny.Uni<Response> filter(ContainerRequestContext ctx) {
        if (!enabled) return null;

        String path = ctx.getUriInfo().getPath();
        if (!shouldProtect(path)) {
            return null;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return io.smallrye.mutiny.Uni.createFrom().item(unauthorizedResponse("missing_authorization"));
        }
        String key = auth.substring(7).trim();
        if (key.isEmpty()) {
            return io.smallrye.mutiny.Uni.createFrom().item(unauthorizedResponse("empty_token"));
        }

        // Cache fast path：Caffeine 命中即同步返回，不付 Uni 调度开销。
        ApiKeyVerifyResult cached = verifier.tryCacheLookup(key);
        if (cached != null) {
            return applyResult(ctx, cached, path);
        }

        // Cache miss：把 verify 丢到 verifyPool，event-loop 立刻让出。
        return io.smallrye.mutiny.Uni
            .createFrom().item(() -> verifier.verify(key))
            .runSubscriptionOn(verifier.verifyExecutor())
            .onItem().transform(result -> applyResultSync(ctx, result, path));
    }

    /**
     * Cache 命中或 fast path 同步路径：apply 结果并返回 Uni（null = continue）。
     */
    private io.smallrye.mutiny.Uni<Response> applyResult(ContainerRequestContext ctx,
                                                         ApiKeyVerifyResult result,
                                                         String path) {
        Response r = applyResultSync(ctx, result, path);
        return r == null ? io.smallrye.mutiny.Uni.createFrom().nullItem()
                         : io.smallrye.mutiny.Uni.createFrom().item(r);
    }

    /** 返回 null = 验证通过继续；非 null = 401 短路。 */
    private Response applyResultSync(ContainerRequestContext ctx,
                                     ApiKeyVerifyResult result, String path) {
        if (!result.valid()) {
            LOG.infof("apikey verify rejected: path=%s reason=%s", path, result.reason());
            return unauthorizedResponse(result.reason() != null ? result.reason() : "invalid_key");
        }
        ctx.setProperty("aster.apikey.userId", result.userId());
        ctx.setProperty("aster.apikey.tenantId", result.tenantId());
        ctx.setProperty("aster.apikey.apiKeyId", result.apiKeyId());
        if (ctx.getHeaderString("X-Tenant-Id") == null) {
            ctx.getHeaders().putSingle("X-Tenant-Id", result.tenantId());
        }
        if (ctx.getHeaderString("X-User-Id") == null) {
            ctx.getHeaders().putSingle("X-User-Id", result.userId());
        }
        if (ctx.getHeaderString("X-Api-Key-Id") == null) {
            ctx.getHeaders().putSingle("X-Api-Key-Id", result.apiKeyId());
        }
        if (ctx.getHeaderString("X-User-Role") == null) {
            ctx.getHeaders().putSingle("X-User-Role", "MEMBER");
        }
        return null;
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
        return new WebApplicationException(unauthorizedResponse(reason));
    }

    private static Response unauthorizedResponse(String reason) {
        return Response.status(401)
            .entity(Map.of(
                "error", "unauthorized",
                "reason", reason,
                "message", "Invalid or revoked API key. See https://aster-lang.cloud/billing/api-keys"
            ))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }
}
