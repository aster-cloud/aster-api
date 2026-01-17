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


    /**
     * 加载租户密钥
     *
     * ⚠️ 严重安全警告 ⚠️
     * 当前实现使用可预测的密钥模式，任何知道租户ID的人都可以伪造签名！
     * 这是一个临时实现，仅用于开发和测试环境。
     *
     * 生产环境必须替换为以下方案之一：
     * 1. 从 OCI Vault 加载密钥
     * 2. 从数据库的加密字段加载
     * 3. 从环境变量或配置文件加载（需要安全存储）
     *
     * TODO: 在 Phase 1 完成后立即实现真实的密钥管理
     */
    private String loadSecret(String tenantId) {
        return secretCache.get(tenantId, k -> {
            // ⚠️ 不安全的临时实现 - 必须在生产环境前替换 ⚠️
            // 使用可预测的密钥允许任何人伪造签名
            String tempSecret = "temp-secret-key-" + tenantId;

            // 记录警告日志
            java.util.logging.Logger.getLogger(getClass().getName())
                .warning("使用不安全的临时密钥！租户: " + tenantId + " - 生产环境必须替换为真实密钥管理");

            return tempSecret;
        });
    }
}
