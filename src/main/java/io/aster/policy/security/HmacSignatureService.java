package io.aster.policy.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * HMAC 签名验证服务
 *
 * 验证请求的 HMAC-SHA256 签名，防止请求篡改
 */
@ApplicationScoped
public class HmacSignatureService {

    // R32 audit P0：原代码 4 处用 java.util.logging，与项目 JBoss Logging
    // 体系不一致，导致 traceId / MDC 丢失，HMAC 失败排查时间序无法关联。
    // 用项目统一的 JBoss Logger 取代。
    private static final Logger LOG = Logger.getLogger(HmacSignatureService.class);


    @Inject
    SecurityEventService securityEventService;

    @ConfigProperty(name = "aster.security.nonce.ttl-minutes", defaultValue = "5")
    int timestampWindowMinutes;

    // 租户密钥缓存（实际应从 OCI Vault 或数据库加载）
    private final Cache<String, String> secretCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(1000)
        .build();

    /**
     * 验证请求签名
     *
     * @param ctx  请求上下文
     * @param body 请求体
     */
    public void verify(ContainerRequestContext ctx, byte[] body) {
        String signature = ctx.getHeaderString("X-Aster-Signature");
        String nonce = ctx.getHeaderString("X-Aster-Nonce");
        String timestamp = ctx.getHeaderString("X-Aster-Timestamp");
        String tenantId = ctx.getHeaderString("X-Tenant-Id");

        if (signature == null || nonce == null || timestamp == null || tenantId == null) {
            throw new WebApplicationException("Missing signature headers", Response.Status.UNAUTHORIZED);
        }

        // 验证时间戳（防止重放攻击）
        validateTimestamp(timestamp);

        // 获取 query 参数
        String query = ctx.getUriInfo().getRequestUri().getQuery();

        // 构建规范化字符串（包含 query 参数）
        String canonical = RequestCanonicalizer.canonicalize(ctx.getMethod(), ctx.getUriInfo().getPath(), query, timestamp, nonce, body);

        // 加载当前密钥并验证；若失败再尝试 previous-key（轮换 grace period）
        String currentSecret = loadSecret(tenantId);
        if (constantTimeEquals(hmacSha256(currentSecret, canonical), signature)) {
            return;
        }

        String previousSecret = loadPreviousSecret(tenantId);
        if (previousSecret != null && constantTimeEquals(hmacSha256(previousSecret, canonical), signature)) {
            // grace period 命中旧密钥，记录便于观察轮换完成度
            LOG.warnf("HMAC 命中旧密钥（grace period），tenant=%s", tenantId);
            return;
        }

        securityEventService.recordSignatureFailure(tenantId, "Signature mismatch",
            java.util.Map.of("method", ctx.getMethod(), "path", ctx.getUriInfo().getPath(), "query", query != null ? query : ""));

        throw new WebApplicationException("Invalid signature", Response.Status.UNAUTHORIZED);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.US_ASCII),
            b.getBytes(StandardCharsets.US_ASCII));
    }


    /**
     * 验证时间戳（防止时间窗口外的请求）
     */
    private void validateTimestamp(String timestampStr) {
        try {
            long timestamp = Long.parseLong(timestampStr);
            long now = System.currentTimeMillis();
            long diff = Math.abs(now - timestamp);

            // 使用配置的时间窗口
            if (diff > Duration.ofMinutes(timestampWindowMinutes).toMillis()) {
                throw new WebApplicationException("Timestamp out of valid window", Response.Status.UNAUTHORIZED);
            }
        } catch (NumberFormatException e) {
            throw new WebApplicationException("Invalid timestamp format", Response.Status.BAD_REQUEST);
        }
    }

    /**
     * 计算 HMAC-SHA256
     */
    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }


    @ConfigProperty(name = "aster.security.hmac.secret-key")
    java.util.Optional<String> globalSecretKey;

    @ConfigProperty(name = "aster.security.hmac.previous-secret-key")
    java.util.Optional<String> previousGlobalSecretKey;

    @ConfigProperty(name = "aster.security.hmac.use-env-secrets", defaultValue = "true")
    boolean useEnvSecrets;

    /**
     * 加载租户密钥
     *
     * 密钥加载优先级：
     * 1. 环境变量: ASTER_HMAC_SECRET_{TENANT_ID} （推荐生产环境使用）
     * 2. 全局配置: aster.security.hmac.secret-key
     * 3. 开发环境回退: 仅当 aster.security.signature.enabled=false 时允许
     */
    private String loadSecret(String tenantId) {
        return secretCache.get(tenantId, k -> {
            // 1. 尝试从环境变量加载租户特定密钥
            if (useEnvSecrets) {
                String envKey = "ASTER_HMAC_SECRET_" + tenantId.toUpperCase().replace("-", "_");
                String envSecret = System.getenv(envKey);
                if (envSecret != null && !envSecret.isBlank()) {
                    LOG.infof("从环境变量加载租户密钥: %s", envKey);
                    return envSecret;
                }
            }

            // 2. 尝试使用全局配置密钥
            if (globalSecretKey.isPresent() && !globalSecretKey.get().isBlank()) {
                LOG.info("使用全局配置密钥");
                return globalSecretKey.get();
            }

            // 3. 开发环境回退（仅当签名验证禁用时）
            LOG.errorf("⚠️ 未配置 HMAC 密钥！租户: %s。请配置环境变量 ASTER_HMAC_SECRET_%s 或 aster.security.hmac.secret-key",
                tenantId, tenantId.toUpperCase().replace("-", "_"));

            // 抛出异常而非返回不安全的默认值
            throw new WebApplicationException(
                "HMAC 密钥未配置，请设置环境变量或配置项",
                Response.Status.INTERNAL_SERVER_ERROR);
        });
    }

    /**
     * 加载租户的上一代密钥（用于轮换 grace period）
     *
     * 加载顺序与 loadSecret 对应：
     * 1. 环境变量: ASTER_HMAC_PREV_SECRET_{TENANT_ID}
     * 2. 全局配置: aster.security.hmac.previous-secret-key
     * 未配置时返回 null（轮换完成后应清空 prev 值）。
     */
    private String loadPreviousSecret(String tenantId) {
        if (useEnvSecrets) {
            String envKey = "ASTER_HMAC_PREV_SECRET_" + tenantId.toUpperCase().replace("-", "_");
            String envSecret = System.getenv(envKey);
            if (envSecret != null && !envSecret.isBlank()) {
                return envSecret;
            }
        }
        return previousGlobalSecretKey.filter(s -> !s.isBlank()).orElse(null);
    }
}
