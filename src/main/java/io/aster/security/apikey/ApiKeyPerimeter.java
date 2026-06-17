package io.aster.security.apikey;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * #55: 共享的 API-key 鉴权"边界"逻辑，被 REST 层的 {@link ApiKeyAuthFilter}
 * 和 GraphQL 层的 Vert.x route handler（{@code GraphQLApiKeyAuthHandler}）**共用**，
 * 避免两条入口的认证语义漂移。
 *
 * <p>它把"从明文 key 验证 → 校验已验证租户与 header 是否冲突 → 推导权威身份
 * （tenant / role / userId / apiKeyId）"这套决策抽成纯逻辑，返回 {@link Decision}。
 * 调用方负责把决策落到各自的传输层（JAX-RS context property/header 或
 * Vert.x RoutingContext）。
 *
 * <p>验证本身委托给已有的 {@link ApiKeyVerifierService}（cloud verify + HMAC 签名
 * + Caffeine 缓存 + redis snapshot），不重复实现。
 */
@ApplicationScoped
public class ApiKeyPerimeter {

    private static final Logger LOG = Logger.getLogger(ApiKeyPerimeter.class);

    /** RoutingContext / JAX-RS property key —— 已验证身份由认证步骤写入，供下游读取。 */
    public static final String PROP_USER_ID = "aster.apikey.userId";
    public static final String PROP_TENANT_ID = "aster.apikey.tenantId";
    public static final String PROP_API_KEY_ID = "aster.apikey.apiKeyId";
    public static final String PROP_ROLE = "aster.apikey.role";

    @Inject
    ApiKeyVerifierService verifier;

    /**
     * 认证决策结果（纯数据）。{@code allowed=true} 时携带权威身份；否则携带
     * HTTP 状态码 + reason，调用方据此短路。
     */
    public record Decision(
        boolean allowed,
        int statusCode,
        String reason,
        String tenantId,
        String role,
        String userId,
        String apiKeyId
    ) {
        static Decision deny(int code, String reason) {
            return new Decision(false, code, reason, null, null, null, null);
        }

        static Decision allow(String tenantId, String role, String userId, String apiKeyId) {
            return new Decision(true, 200, null, tenantId, role, userId, apiKeyId);
        }
    }

    /**
     * 同步认证：解析 {@code Authorization: Bearer ak_...} 头并据 {@code X-Tenant-Id}
     * header 做跨租户冲突校验。返回授权决策。
     *
     * <p>注意：本方法可能触发 cloud verify 的阻塞 IO（cache miss 时），调用方必须
     * 确保不在 Vert.x event-loop 线程上直接调用（GraphQL handler 用
     * {@code executeBlocking} 包裹；REST filter 已有 verifyPool offload）。
     *
     * @param authorizationHeader 原始 Authorization 头（可为 null）
     * @param tenantHeader 原始 X-Tenant-Id 头（可为 null）
     * @return 授权决策
     */
    public Decision authenticate(String authorizationHeader, String tenantHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Decision.deny(401, "missing_authorization");
        }
        String key = authorizationHeader.substring(7).trim();
        if (key.isEmpty()) {
            return Decision.deny(401, "empty_token");
        }

        ApiKeyVerifyResult result = verifier.verify(key);
        if (!result.valid()) {
            return Decision.deny(401, result.reason() != null ? result.reason() : "invalid_key");
        }

        // 与 ApiKeyAuthFilter 同款租户隔离铁律：valid 结果必须携带权威租户；
        // 缺失即 fail-closed，绝不回退到 "default"。
        String verifiedTenant = result.tenantId();
        if (verifiedTenant == null || verifiedTenant.isBlank()) {
            LOG.warn("apikey valid but tenant binding missing — denying");
            return Decision.deny(403, "invalid_tenant_binding");
        }
        if (isTenantMismatch(verifiedTenant, tenantHeader)) {
            LOG.warnf("apikey tenant mismatch: key_tenant=%s header_tenant=%s — denying",
                sanitize(verifiedTenant), sanitize(tenantHeader));
            return Decision.deny(403, "tenant_mismatch");
        }

        String verifiedRole = result.role() != null && !result.role().isBlank()
            ? result.role() : ApiKeyVerifyResult.DEFAULT_ROLE;
        return Decision.allow(verifiedTenant, verifiedRole, result.userId(), result.apiKeyId());
    }

    /**
     * 租户匹配判定（纯函数）。与 {@link ApiKeyAuthFilter#isTenantMismatch} 语义一致：
     * 调用方未带 X-Tenant-Id 视为"无主张"不算冲突；带了就必须 trim 后精确相等。
     */
    public static boolean isTenantMismatch(String verifiedTenant, String headerTenant) {
        if (headerTenant == null || headerTenant.isBlank()) {
            return false;
        }
        return !headerTenant.trim().equals(verifiedTenant);
    }

    /** 日志注入防护：截断 + 去除控制字符。 */
    private static String sanitize(String input) {
        if (input == null) return "null";
        String s = input.length() > 64 ? input.substring(0, 64) + "..." : input;
        return s.replaceAll("[\\r\\n\\t]", "_");
    }
}
