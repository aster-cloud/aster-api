package io.aster.workflow;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workflow 事件实体 - 使用 Panache Active Record 模式
 *
 * 持久化 workflow 执行事件到 PostgreSQL 分区表，支持事件溯源和状态重放。
 */
@RegisterForReflection
@Entity
@Table(name = "workflow_events")
public class WorkflowEventEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Column(nullable = false)
    public Long sequence;

    @Column(name = "seq", nullable = false)
    public Long seq;

    @Column(name = "event_type", nullable = false, length = 64)
    public String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(name = "idempotency_key", length = 255)
    public String idempotencyKey;

    /**
     * 重试尝试次数，默认为 1，向后兼容无重试场景
     */
    @Column(name = "attempt_number")
    public Integer attemptNumber = 1;

    /**
     * 上一次退避时间（毫秒），用于诊断与回溯重试策略
     */
    @Column(name = "backoff_delay_ms")
    public Long backoffDelayMs;

    /**
     * 失败原因，扩大长度以兼容堆栈信息
     */
    @Column(name = "failure_reason", length = 10000)
    public String failureReason;

    /**
     * 策略版本 ID（Phase 3.1）
     * NULL 表示版本化功能上线前的事件
     */
    @Column(name = "policy_version_id")
    public Long policyVersionId;

    /**
     * 查询指定 workflow 的事件历史
     *
     * @param workflowId workflow 唯一标识符
     * @param fromSeq 起始序列号（包含）
     * @return 事件列表，按序列号升序排列
     */
    public static List<WorkflowEventEntity> findByWorkflowId(UUID workflowId, long fromSeq) {
        return find("workflowId = ?1 AND sequence >= ?2 ORDER BY sequence", workflowId, fromSeq).list();
    }

    /**
     * 查询指定 workflow 下某次重试的事件历史
     *
     * @param workflowId workflow 唯一标识符
     * @param attemptNumber 重试次数
     * @return 事件列表
     */
    public static List<WorkflowEventEntity> findByWorkflowIdAndAttempt(UUID workflowId, int attemptNumber) {
        return find("workflowId = ?1 AND attemptNumber = ?2 ORDER BY sequence", workflowId, attemptNumber).list();
    }

    /**
     * 根据幂等性键查询事件
     *
     * @param workflowId workflow 唯一标识符
     * @param idempotencyKey 幂等性键
     * @return 事件实例，如果不存在则返回 empty
     */
    public static Optional<WorkflowEventEntity> findByIdempotencyKey(UUID workflowId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return find("workflowId = ?1 AND idempotencyKey = ?2", workflowId, idempotencyKey)
                .firstResultOptional();
    }

    /**
     * 统计 workflow 的事件数量
     *
     * @param workflowId workflow 唯一标识符
     * @return 事件总数
     */
    public static long countByWorkflowId(UUID workflowId) {
        return count("workflowId", workflowId);
    }

    /**
     * 判斷指定類型事件是否已存在
     *
     * @param workflowId workflow 唯一標識符
     * @param eventType 事件類型
     * @return true 表示已存在
     */
    public static boolean hasEvent(UUID workflowId, String eventType) {
        return count("workflowId = ?1 AND eventType = ?2", workflowId, eventType) > 0;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public Long getBackoffDelayMs() {
        return backoffDelayMs;
    }

    public void setBackoffDelayMs(Long backoffDelayMs) {
        this.backoffDelayMs = backoffDelayMs;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
