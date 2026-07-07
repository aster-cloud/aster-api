package io.aster.audit.outbox;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 通用 Outbox 实体基类
 *
 * @param <P> 反序列化后的 payload 类型
 */
@RegisterForReflection
@MappedSuperclass
public abstract class GenericOutboxEntity<P> extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public OutboxStatus status = OutboxStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    public String payload;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    /**
     * 租约令牌（issue #119）：每次 claim（事务A 标 RUNNING）生成一个新值，
     * finalize（事务B）以 {@code status=RUNNING AND leaseToken=?} 为条件推进终态，
     * reclaim 回收过期 RUNNING 时清空。用于在「长事务拆分 + stale reclaim 重投递」下
     * 区分同一行的不同 claim attempt，避免旧 attempt 迟到的 finalize 错误覆盖新 attempt。
     */
    @Column(name = "lease_token", length = 64)
    public String leaseToken;

    @PrePersist
    protected void assignCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * 返回事件类型，用于审计与日志记录
     */
    public abstract String getEventType();

    /**
     * 将 JSON payload 反序列化为领域对象
     *
     * @return 解析后的 payload
     */
    public abstract P deserializePayload();
}
