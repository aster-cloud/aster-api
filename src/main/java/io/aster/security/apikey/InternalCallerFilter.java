package io.aster.security.apikey;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Internal caller filter for /api/v1/policies/evaluate-source
 *
 * /evaluate-source 接受请求体里的 CNL 源码，绕过审核流。仅 cloud BFF 可用于
 * dashboard 预览/playground，**禁止外部客户调用**——否则审核流可被旁路。
 *
 * 守门：要求 X-Internal-Caller=cloud-bff + HMAC 签名（复用 PlanGate HMAC key）。
 *
 * 优先级 = AUTHENTICATION + 50（在 RequestSignatureFilter 之后、ApiKeyAuthFilter 之前）。
 */
@ApplicationScoped
public class InternalCallerFilter {

    private static final Logger LOG = Logger.getLogger(InternalCallerFilter.class);

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @ConfigProperty(name = "aster.security.evaluate-source.public", defaultValue = "false")
    boolean evaluateSourcePublic;

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 50)
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String p = path.startsWith("/") ? path : "/" + path;
        if (!p.equals("/api/v1/policies/evaluate-source")) {
            return;
        }
        if (evaluateSourcePublic) {
            // 显式 opt-in 才允许公开（dev/测试用；生产应保持 false）
            return;
        }

        String caller = ctx.getHeaderString("X-Internal-Caller");
        String timestamp = ctx.getHeaderString("X-Aster-Timestamp");
        String signature = ctx.getHeaderString("X-Internal-Signature");

        if (!"cloud-bff".equals(caller) || timestamp == null || signature == null) {
            throw forbidden("evaluate_source_internal_only");
        }

        // 时间戳防重放（5 min 窗口）
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw forbidden("invalid_timestamp");
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > 300) {
            throw forbidden("stale_timestamp");
        }

        if (hmacKey.isEmpty()) {
            // 配置错误：生产必须配 HMAC；如果缺则全部拒绝
            LOG.warn("evaluate-source called without HMAC key configured; rejecting");
            throw forbidden("hmac_not_configured");
        }
        String expected = sign(hmacKey.get(), "POST\n/api/v1/policies/evaluate-source\n" + ts);
        if (!constantTimeEquals(expected, signature)) {
            throw forbidden("invalid_signature");
        }
    }

    private static WebApplicationException forbidden(String reason) {
        return new WebApplicationException(
            Response.status(403)
                .entity(Map.of(
                    "error", "evaluate_source_forbidden",
                    "reason", reason,
                    "message", "POST /evaluate-source is internal-only. Use /evaluate (DB-backed) for production policies."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build()
        );
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
