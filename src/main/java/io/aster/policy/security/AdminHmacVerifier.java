package io.aster.policy.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * 管理端点内部 HMAC 验签（ADR 0021 抽出复用）。
 *
 * <p>与 {@code LexiconAdminResource} 同一套 admin 验签方案：{@code X-Internal-Signature}
 * + {@code X-Aster-Timestamp} + {@code X-Aster-Nonce}，密钥 {@code aster.plan-gate.hmac-key}。
 * canonical 串 = {@code method\npath\nts\nnonce\ncontentType\ncontentLength\nbodySha256}。
 *
 * <p><b>信任边界（Codex 审查）</b>：这是**资源内部 HMAC**，不依赖 {@code RoleEnforcementFilter}
 * 读的 {@code X-User-Role}（后者只在角色经 ApiKeyAuthFilter 权威化时才可信）。admin 写端点
 * 用内部 HMAC = 调用方须持有服务端密钥，等价于"内部可信调用方"边界。
 *
 * <p>nonce 一旦被观察到（无论签名对错）就消费掉（防 nonce-grabbing DoS），5min TTL 自然过期。
 */
@ApplicationScoped
public class AdminHmacVerifier {

    private static final Logger LOG = Logger.getLogger(AdminHmacVerifier.class);
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final long NONCE_TTL_SECONDS = MAX_CLOCK_SKEW_SECONDS + 60;
    private static final String NONCE_KEY_PREFIX = "aster:admin:nonce:";

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @Inject
    Instance<RedisDataSource> redisDataSource;

    /**
     * 本地 nonce 兜底（仅明确无 Redis 的 dev/本地场景）。生产优先 Redis 分布式 nonce
     * （SET NX EX，跨 pod 唯一）；**Redis 已配置却 claim 异常 → fail-closed 拒绝**，
     * 不退本地（见 {@link #claimNonce}）。
     */
    private final Cache<String, Boolean> localNonces = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(NONCE_TTL_SECONDS))
        .maximumSize(50_000)
        .build();

    /**
     * 验签。失败抛 403 {@link WebApplicationException}。
     *
     * @param headers     请求头（取 X-Internal-Signature / X-Aster-Timestamp / X-Aster-Nonce）
     * @param method      HTTP 方法（PUT/POST/DELETE…）
     * @param path        规范路径（如 {@code /api/v1/admin/messages/en-US}）
     * @param contentType 请求 content-type（非 body 请求传 null）
     * @param contentLen  body 长度（非 body 请求传 0）
     * @param bodySha256  body 的 sha256 hex（非 body 请求传 null）
     */
    public void verify(HttpHeaders headers, String method, String path,
                       String contentType, long contentLen, String bodySha256) {
        if (hmacKey.isEmpty()) {
            throw forbidden("hmac_not_configured", "server has no aster.plan-gate.hmac-key set");
        }
        String tsStr = headers.getHeaderString("X-Aster-Timestamp");
        String nonce = headers.getHeaderString("X-Aster-Nonce");
        String sig = headers.getHeaderString("X-Internal-Signature");
        if (tsStr == null || nonce == null || sig == null) {
            throw forbidden("missing_signature_headers",
                "X-Aster-Timestamp, X-Aster-Nonce, X-Internal-Signature required");
        }

        long ts;
        try {
            ts = Long.parseLong(tsStr);
        } catch (NumberFormatException e) {
            throw forbidden("invalid_timestamp", "timestamp not a number");
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > MAX_CLOCK_SKEW_SECONDS) {
            throw forbidden("stale_timestamp", "clock skew exceeds 5 min");
        }

        // nonce 原子预约：一旦观察到就消费（不在 invalid_signature 时回滚，防 nonce-grabbing DoS）。
        // **分布式 nonce（Codex 审查：跨 pod replay）**：用 Redis SET NX EX 原子占位，
        // 全集群唯一；Redis 不可用退回本地（单 pod 防重放）。
        if (!claimNonce(nonce)) {
            throw forbidden("replayed_nonce", "nonce already used within window");
        }

        String ct = contentType == null ? "" : contentType;
        String sha = bodySha256 == null ? "" : bodySha256;
        String canonical = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n"
            + ct + "\n" + contentLen + "\n" + sha;
        String expected = hmacSha256Hex(hmacKey.get(), canonical);
        if (!constantTimeEqualsHex(expected, sig)) {
            throw forbidden("invalid_signature", "HMAC mismatch");
        }
    }

    /**
     * 原子占用 nonce（true=首次，false=已用过/拒绝）。Redis SET NX EX 跨 pod 唯一。
     *
     * <p>**fail-closed（Codex 复审）**：Redis **已配置但 claim 异常** → 返回 false（拒绝），
     * 不退回本地——否则瞬时 Redis 故障会让跨 pod replay 防护失效。仅在**明确无 Redis**
     * （本地/dev，{@code isUnsatisfied}）时才走本地 Caffeine（单 pod 防重放）。
     */
    private boolean claimNonce(String nonce) {
        if (redisDataSource != null && redisDataSource.isResolvable() && !redisDataSource.isUnsatisfied()) {
            try {
                ValueCommands<String, String> v = redisDataSource.get().value(String.class);
                // SET key val NX EX ttl GET —— 带 GET 返回旧值：null=之前不存在（首次占位成功），
                // 非 null=已存在（重放）。
                String prev = v.setGet(NONCE_KEY_PREFIX + nonce, "1",
                    new SetArgs().nx().ex(NONCE_TTL_SECONDS));
                return prev == null;
            } catch (Exception e) {
                // fail-closed：Redis 已配置却失败 → 拒绝，宁可误拒也不让 replay 防护失效。
                LOG.warnf("admin nonce Redis 占位异常 → fail-closed 拒绝: %s", e.getMessage());
                return false;
            }
        }
        // 明确无 Redis（dev/本地）→ 本地兜底。
        return localNonces.asMap().putIfAbsent(nonce, Boolean.TRUE) == null;
    }

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static boolean constantTimeEqualsHex(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        return MessageDigest.isEqual(
            a.toLowerCase().getBytes(StandardCharsets.US_ASCII),
            b.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private static WebApplicationException forbidden(String error, String message) {
        return new WebApplicationException(
            Response.status(403)
                .entity(Map.of("error", error, "message", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
