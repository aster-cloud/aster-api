package io.aster.policy.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

        // 加载租户密钥
        String secret = loadSecret(tenantId);

        // 获取 query 参数
        String query = ctx.getUriInfo().getRequestUri().getQuery();

        // 构建规范化字符串（包含 query 参数）
        String canonical = RequestCanonicalizer.canonicalize(ctx.getMethod(), ctx.getUriInfo().getPath(), query, timestamp, nonce, body);

        // 计算期望的签名
        String expected = hmacSha256(secret, canonical);

        // 常量时间比较
        if (!MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII))) {

            securityEventService.recordSignatureFailure(tenantId, "Signature mismatch",
                java.util.Map.of("method", ctx.getMethod(), "path", ctx.getUriInfo().getPath(), "query", query != null ? query : ""));

            throw new WebApplicationException("Invalid signature", Response.Status.UNAUTHORIZED);
        }
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
                    java.util.logging.Logger.getLogger(getClass().getName())
                        .info("从环境变量加载租户密钥: " + envKey);
                    return envSecret;
                }
            }

            // 2. 尝试使用全局配置密钥
            if (globalSecretKey.isPresent() && !globalSecretKey.get().isBlank()) {
                java.util.logging.Logger.getLogger(getClass().getName())
                    .info("使用全局配置密钥");
                return globalSecretKey.get();
            }

            // 3. 开发环境回退（仅当签名验证禁用时）
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(getClass().getName());
            logger.severe("⚠️ 未配置 HMAC 密钥！租户: " + tenantId);
            logger.severe("请配置环境变量 ASTER_HMAC_SECRET_" + tenantId.toUpperCase().replace("-", "_"));
            logger.severe("或设置 aster.security.hmac.secret-key 配置项");

            // 抛出异常而非返回不安全的默认值
            throw new WebApplicationException(
                "HMAC 密钥未配置，请设置环境变量或配置项",
                Response.Status.INTERNAL_SERVER_ERROR);
        });
    }
}
