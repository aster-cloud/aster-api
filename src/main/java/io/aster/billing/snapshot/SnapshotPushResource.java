package io.aster.billing.snapshot;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Snapshot 推送接收端（cloud → aster-api）
 *
 * cloud 在以下场景调用：
 *   - user.plan / subscriptionStatus / aiBannedUntil 变更
 *   - apiKey 创建 / 撤销
 *   - DUN-4 auto-downgrade
 *
 * 安全：HMAC 签名（共享 ASTER_PLAN_GATE_HMAC_KEY）
 */
@Path("/api/internal/snapshot")
public class SnapshotPushResource {

    private static final Logger LOG = Logger.getLogger(SnapshotPushResource.class);

    @Inject
    LocalQuotaSnapshotService snapshot;

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @POST
    @Path("/user/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response pushUser(
        @PathParam("userId") String userId,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature,
        String bodyJson
    ) {
        Response auth = verify("POST\n/api/internal/snapshot/user/" + userId, timestamp, signature);
        if (auth != null) return auth;

        try {
            JsonObject json = new JsonObject(bodyJson);
            UserSnapshot s = new UserSnapshot(
                userId,
                json.getString("plan", "free"),
                json.getLong("apiCallsLimit", 0L),
                json.getString("subscriptionStatus", null),
                json.getLong("aiBannedUntilEpochMs", null),
                json.getLong("gracePeriodEndsEpochMs", null)
            );
            snapshot.setUser(s);
            LOG.infof("snapshot user pushed: userId=%s plan=%s limit=%d",
                userId, s.plan(), s.apiCallsLimit());
            return Response.ok("{\"ok\":true,\"userId\":\"" + userId + "\"}").build();
        } catch (Exception e) {
            LOG.warnf("pushUser failed: %s", e.getMessage());
            return Response.status(400).entity("{\"error\":\"bad_payload\"}").build();
        }
    }

    @POST
    @Path("/apikey/{keyHash}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response pushApiKey(
        @PathParam("keyHash") String keyHash,
        @HeaderParam("X-Aster-Timestamp") String timestamp,
        @HeaderParam("X-Aster-Signature") String signature,
        String bodyJson
    ) {
        Response auth = verify("POST\n/api/internal/snapshot/apikey/" + keyHash, timestamp, signature);
        if (auth != null) return auth;

        if (keyHash.length() != 64) {
            return Response.status(400).entity("{\"error\":\"bad_keyhash\"}").build();
        }

        try {
            JsonObject json = new JsonObject(bodyJson);
            boolean valid = json.getBoolean("valid", false);
            ApiKeySnapshot s;
            if (valid) {
                s = new ApiKeySnapshot(
                    true, null,
                    json.getString("apiKeyId"),
                    json.getString("userId"),
                    json.getString("tenantId"),
                    json.getString("plan"),
                    json.getString("role"),
                    json.getLong("revokedAtEpochMs", null)
                );
            } else {
                s = ApiKeySnapshot.invalid(json.getString("reason", "invalid"));
            }
            snapshot.setApiKey(keyHash, s);
            LOG.infof("snapshot apikey pushed: hash=%.8s... valid=%s", keyHash, s.valid());
            return Response.ok("{\"ok\":true}").build();
        } catch (Exception e) {
            LOG.warnf("pushApiKey failed: %s", e.getMessage());
            return Response.status(400).entity("{\"error\":\"bad_payload\"}").build();
        }
    }

    private Response verify(String canonical, String timestamp, String signature) {
        if (hmacKey.isEmpty()) return null; // dev/test 跳过
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
        String expected = sign(hmacKey.get(), canonical + "\n" + ts);
        if (!constantTimeEquals(expected, signature)) {
            return Response.status(401).entity("invalid signature").build();
        }
        return null;
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
