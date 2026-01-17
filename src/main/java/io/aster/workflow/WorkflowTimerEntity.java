package io.aster.workflow;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Workflow 定时器实体 - 使用 Panache Active Record 模式
 *
 * 支持 durable timers，用于延迟执行、超时检测和定时任务。
 */
@RegisterForReflection
@Entity
@Table(name = "workflow_timers")
public class WorkflowTimerEntity extends PanacheEntityBase {

    @Id
    @Column(name = "timer_id")
    public UUID timerId;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Column(name = "step_id", nullable = true, length = 128)
    public String stepId;

    @Column(name = "fire_at", nullable = false)
    public Instant fireAt;

    /** Alias for fireAt to match implementation guide naming */
    public Instant getNextExecutionTime() {
        return fireAt;
    }

    public void setNextExecutionTime(Instant nextExecutionTime) {
        this.fireAt = nextExecutionTime;
    }

    @Column(name = "interval_millis", nullable = true)
    public Long intervalMillis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(nullable = false, length = 32)
    public String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /**
     * 查询指定 workflow 的所有定时器
     *
     * @param workflowId workflow 唯一标识符
     * @return 定时器列表
     */
    public static List<WorkflowTimerEntity> findByWorkflowId(UUID workflowId) {
        return find("workflowId", workflowId).list();
    }

    /**
     * 查询已到期且待触发的定时器
     *
     * @param limit 最大返回数量
     * @return 待触发的定时器列表
     */
    public static List<WorkflowTimerEntity> findFiredTimers(int limit) {
        return find("status = 'PENDING' AND fireAt <= ?1 ORDER BY fireAt", Instant.now())
                .page(0, limit)
                .list();
    }

    /**
     * 统计待触发的定时器数量
     *
     * @return 定时器数量
     */
    public static long countPending() {
        return count("status", "PENDING");
    }

    /**
     * 取消指定 workflow 的所有定时器
     *
     * @param workflowId workflow 唯一标识符
     * @return 被取消的定时器数量
     */
    public static long cancelByWorkflowId(UUID workflowId) {
        return update("status = 'CANCELLED' WHERE workflowId = ?1 AND status = 'PENDING'", workflowId);
    }
}
