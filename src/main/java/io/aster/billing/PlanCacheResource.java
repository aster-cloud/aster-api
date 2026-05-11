package io.aster.billing;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.HeaderParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Plan 缓存失效回调
 *
 * aster-cloud 在 webhook 处理 plan 升级 / 降级 / 切换 legacyTier 后，
 * 立即调用此端点让 aster-api 端的 Caffeine 缓存失效，缩短 5 min 生效延迟。
 *
 * 安全：复用 PlanGate 的 HMAC 共享密钥（双向用同一把）。
 */
@Path("/api/internal/plan-cache")
public class PlanCacheResource {

    private static final Logger LOG = Logger.getLogger(PlanCacheResource.class);

    @Inject
    PlanGateService planGate;

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @DELETE
    @Path("/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response invalidate(
        @PathParam("tenantId") String tenantId,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature
    ) {
        // 配置了密钥则强制验签；dev 缺省时跳过（与 cloud 端策略对称）
        if (hmacKey.isPresent()) {
            if (timestamp == null || signature == null) {
                return Response.status(401).entity("missing signature headers").build();
            }
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return Response.status(401).entity("invalid timestamp").build();
            }
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - ts) > 300) {
                return Response.status(401).entity("stale timestamp").build();
            }
            String path = "/api/internal/plan-cache/" + tenantId;
            String expected = sign(hmacKey.get(), "DELETE\n" + path + "\n" + ts);
            if (!constantTimeEquals(expected, signature)) {
                return Response.status(401).entity("invalid signature").build();
            }
        }

        planGate.invalidate(tenantId);
        LOG.infof("Plan cache invalidated for tenant=%s", tenantId);
        return Response.ok("{\"invalidated\":\"" + tenantId + "\"}").build();
    }

    private static String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
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
