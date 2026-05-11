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
    }

    /**
     * 仅保护公开生产路径
     */
    private static boolean shouldProtect(String path) {
        // 归一化前导斜杠
        String p = path.startsWith("/") ? path : "/" + path;
        if (!p.startsWith("/api/v1/policies/")) return false;
        // 仅保护 evaluate / evaluate-json / evaluate/batch
        // 显式排除 evaluate-source（由 InternalCallerFilter 守护）
        if (p.equals("/api/v1/policies/evaluate")) return true;
        if (p.equals("/api/v1/policies/evaluate-json")) return true;
        if (p.equals("/api/v1/policies/evaluate/batch")) return true;
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
