package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 安全事件日志表
 *
 * 记录所有安全相关事件：签名失败、Nonce 重放、审批拒绝、沙箱违规等
 */
@RegisterForReflection
@Entity
@Table(name = "security_event", indexes = {
    @Index(name = "idx_security_event_type", columnList = "event_type"),
    @Index(name = "idx_security_event_tenant", columnList = "tenant_id"),
    @Index(name = "idx_security_event_handled", columnList = "handled")
})
public class SecurityEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "tenant_id")
    public String tenantId;

    @Column(name = "policy_version_id")
    public Long policyVersionId;

    /**
     * 事件类型：SIGNATURE_FAILURE, NONCE_REPLAY, APPROVAL_REJECTED, SANDBOX_VIOLATION 等
     */
    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;

    /**
     * 严重程度：LOW, MEDIUM, HIGH
     */
    @Column(name = "severity", nullable = false, length = 16)
    public String severity;

    /**
     * 事件详细信息（JSON 格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    public Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt = Instant.now();

    @Column(name = "handled", nullable = false)
    public Boolean handled = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public SecurityEvent() {
    }

    public SecurityEvent(String tenantId, String eventType, String severity, Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.severity = severity;
        this.payload = payload;
        this.occurredAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public static java.util.List<SecurityEvent> findUnhandled() {
        return find("handled = false order by occurredAt desc").list();
    }
}
