package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;

import java.time.Instant;

/**
 * Nonce 防重放表
 *
 * 用于防止请求重放攻击，每个 nonce 只能使用一次
 * 配合 HMAC 签名验证实现请求幂等性
 */
@RegisterForReflection
@Entity
@Table(name = "used_nonce", indexes = {
    @Index(name = "idx_used_nonce_expires_at", columnList = "expires_at"),
    @Index(name = "idx_used_nonce_request_hash", columnList = "tenant_id,request_hash")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_used_nonce_tenant_nonce", columnNames = {"tenant_id", "nonce"})
})
public class UsedNonce extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 租户ID
     */
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /**
     * Nonce 值（客户端生成的唯一标识）
     */
    @Column(name = "nonce", nullable = false, length = 64)
    public String nonce;

    /**
     * 请求哈希（method|path|query|body 的 SHA256）
     * 用于检测相同请求的重放
     */
    @Column(name = "request_hash", nullable = false, length = 64)
    public String requestHash;

    /**
     * 过期时间（建议 5 分钟 TTL）
     */
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    // 无参构造函数（JPA 要求）
    public UsedNonce() {
    }

    /**
     * 创建 Nonce 记录
     *
     * @param tenantId    租户ID
     * @param nonce       Nonce 值
     * @param requestHash 请求哈希
     * @param expiresAt   过期时间
     */
    public UsedNonce(String tenantId, String nonce, String requestHash, Instant expiresAt) {
        this.tenantId = tenantId;
        this.nonce = nonce;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /**
     * 原子性插入 Nonce（如果不存在）
     * 利用数据库唯一约束保证并发安全
     *
     * @param tenantId    租户ID
     * @param nonce       Nonce 值
     * @param requestHash 请求哈希
     * @param expiresAt   过期时间
     * @return 是否插入成功（false 表示 nonce 已存在）
     */
    @Transactional
    public static boolean persistIfNotExists(String tenantId, String nonce, String requestHash, Instant expiresAt) {
        // 用原生 INSERT ... ON CONFLICT DO NOTHING：唯一约束冲突时返回 0 行、**不抛异常也不
        // 中止事务**。此前的 try/catch persist() 在 Postgres 上会因失败 INSERT 把整个事务标记为
        // aborted → 调用方（NonceService.ensureFresh）随后要写 security_event 记录重放时报
        // "current transaction is aborted" → 重放本应返回 409 却被掩盖成 500（红队 P0-C IT 暴露）。
        int inserted = getEntityManager()
            .createNativeQuery(
                "INSERT INTO used_nonce (tenant_id, nonce, request_hash, expires_at, created_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5) "
                // 列推断而非约束名：迁移里是匿名 UNIQUE(tenant_id, nonce)（Postgres 自动命名
                // used_nonce_tenant_id_nonce_key，非 JPA 注解的 uk_used_nonce_tenant_nonce）。
                + "ON CONFLICT (tenant_id, nonce) DO NOTHING")
            .setParameter(1, tenantId)
            .setParameter(2, nonce)
            .setParameter(3, requestHash)
            .setParameter(4, expiresAt)
            .setParameter(5, Instant.now())
            .executeUpdate();
        return inserted > 0;
    }

    /**
     * 清理过期的 Nonce 记录
     *
     * @return 删除的记录数
     */
    public static long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }
}
