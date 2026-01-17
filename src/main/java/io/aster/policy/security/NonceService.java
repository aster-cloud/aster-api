package io.aster.policy.security;

import io.aster.policy.entity.UsedNonce;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Nonce 服务
 *
 * 防止请求重放攻击，确保每个 nonce 只能使用一次
 */
@ApplicationScoped
public class NonceService {

    @Inject
    SecurityEventService securityEventService;

    @ConfigProperty(name = "aster.security.nonce.ttl-minutes", defaultValue = "5")
    int nonceTtlMinutes;

    /**
     * 验证并记录 Nonce
     * 如果 nonce 已存在，抛出异常
     *
     * @param tenantId    租户ID
     * @param nonce       Nonce 值
     * @param requestHash 请求哈希
     */
    @Transactional
    public void ensureFresh(String tenantId, String nonce, String requestHash) {
        Instant expiresAt = Instant.now().plus(nonceTtlMinutes, ChronoUnit.MINUTES);

        boolean inserted = UsedNonce.persistIfNotExists(tenantId, nonce, requestHash, expiresAt);

        if (!inserted) {
            securityEventService.recordNonceReplay(tenantId, nonce,
                java.util.Map.of("requestHash", requestHash));
            throw new WebApplicationException("Nonce replay detected", Response.Status.CONFLICT);
        }
    }

    /**
     * 清理过期的 Nonce 记录
     * 应由定时任务调用
     *
     * @return 删除的记录数
     */
    @Transactional
    public long evictExpired() {
        return UsedNonce.deleteExpired();
    }
}
