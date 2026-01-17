package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 审计日志实体 - 持久化审计记录到数据库
 *
 * 用于合规审计和事后调查，记录所有关键策略操作：
 * - 策略评估
 * - 策略创建
 * - 策略回滚
 *
 * 所有 PII 在写入前已被脱敏。
 */
@RegisterForReflection
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_tenant", columnList = "tenant_id"),
    @Index(name = "idx_audit_event_type", columnList = "event_type"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_policy", columnList = "policy_module, policy_function")
})
public class AuditLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 事件类型：POLICY_EVALUATION, POLICY_CREATED, POLICY_ROLLBACK
     */
    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;

    /**
     * 事件发生时间
     */
    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;

    /**
     * 租户 ID（多租户隔离）
     */
    @Column(name = "tenant_id", nullable = false, length = 100)
    public String tenantId;

    /**
     * 执行者 ID（从 X-User-Id 头部提取）
     */
    @Column(name = "performed_by", length = 100)
    public String performedBy;

    /**
     * 策略模块名称
     */
    @Column(name = "policy_module", length = 200)
    public String policyModule;

    /**
     * 策略函数名称
     */
    @Column(name = "policy_function", length = 200)
    public String policyFunction;

    /**
     * 策略 ID（仅用于 POLICY_CREATED 和 POLICY_ROLLBACK）
     */
    @Column(name = "policy_id", length = 100)
    public String policyId;

    /**
     * 原版本号（仅用于 POLICY_ROLLBACK）
     */
    @Column(name = "from_version")
    public Long fromVersion;

    /**
     * 目标版本号（仅用于 POLICY_CREATED 和 POLICY_ROLLBACK）
     */
    @Column(name = "to_version")
    public Long toVersion;

    /**
     * 执行时间（毫秒，仅用于 POLICY_EVALUATION）
     */
    @Column(name = "execution_time_ms")
    public Long executionTimeMs;

    /**
     * 是否成功（仅用于 POLICY_EVALUATION）
     */
    @Column(name = "success")
    public Boolean success;

    /**
     * 回滚原因（仅用于 POLICY_ROLLBACK）
     */
    @Column(name = "reason", length = 500)
    public String reason;

    /**
     * 错误信息（策略评估失败时记录）
     */
    @Column(name = "error_message", length = 1000)
    public String errorMessage;

    /**
     * 额外注释（可选）
     */
    @Column(name = "notes", length = 1000)
    public String notes;

    /**
     * 扩展元数据（JSON 字符串）
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;

    /**
     * 客户端 IP 地址（已脱敏）
     */
    @Column(name = "client_ip", length = 50)
    public String clientIp;

    /**
     * User-Agent（可选）
     */
    @Column(name = "user_agent", length = 500)
    public String userAgent;

    /**
     * 前一条审计记录的哈希值（SHA256 hex，用于构建防篡改链）
     * NULL 表示该租户的第一条记录（genesis block）
     */
    @Column(name = "prev_hash", length = 64)
    public String prevHash;

    /**
     * 当前记录的哈希值（SHA256 hex）
     * 计算规则：SHA256(prev_hash + event_type + timestamp + tenant_id + policy_module + policy_function + success)
     */
    @Column(name = "current_hash", length = 64)
    public String currentHash;

    // Constructors
    public AuditLog() {
    }

    /**
     * 查询指定租户的所有审计日志
     */
    public static java.util.List<AuditLog> findByTenant(String tenantId) {
        return list("tenantId = ?1 order by timestamp desc", tenantId);
    }

    /**
     * 查询指定事件类型的审计日志
     */
    public static java.util.List<AuditLog> findByEventType(String eventType, String tenantId) {
        return list("eventType = ?1 and tenantId = ?2 order by timestamp desc", eventType, tenantId);
    }

    /**
     * 查询指定策略的审计日志
     */
    public static java.util.List<AuditLog> findByPolicy(String policyModule, String policyFunction, String tenantId) {
        return list("policyModule = ?1 and policyFunction = ?2 and tenantId = ?3 order by timestamp desc",
            policyModule, policyFunction, tenantId);
    }

    /**
     * 查询指定时间范围的审计日志
     */
    public static java.util.List<AuditLog> findByTimeRange(Instant startTime, Instant endTime, String tenantId) {
        return list("timestamp >= ?1 and timestamp <= ?2 and tenantId = ?3 order by timestamp desc",
            startTime, endTime, tenantId);
    }

    /**
     * 查询指定租户的最新哈希值（用于构建哈希链）
     * 返回 NULL 表示该租户的第一条记录（genesis block）
     */
    public static String findLatestHash(String tenantId) {
        AuditLog log = find("tenantId = ?1 and currentHash is not null order by timestamp desc, id desc", tenantId)
            .firstResult();
        return log != null ? log.currentHash : null;
    }
}
