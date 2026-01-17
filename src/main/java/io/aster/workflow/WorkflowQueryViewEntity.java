package io.aster.workflow;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * CQRS 查询视图实体 - Read Model
 *
 * 分离读写操作，提供优化的查询性能。
 * - Write Model: workflow_state + workflow_events（事件溯源）
 * - Read Model: workflow_query_view（预计算视图）
 */
@RegisterForReflection
@Entity
@Table(name = "workflow_query_view")
public class WorkflowQueryViewEntity extends PanacheEntityBase {

    @Id
    @Column(name = "workflow_id")
    public UUID workflowId;

    // 基本状态信息
    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "policy_version_id")
    public Long policyVersionId;

    // 时间追踪
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    // 执行结果与错误
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    // 统计信息（预计算）
    @Column(name = "total_events", nullable = false)
    public Long totalEvents = 0L;

    @Column(name = "total_steps", nullable = false)
    public Integer totalSteps = 0;

    @Column(name = "completed_steps", nullable = false)
    public Integer completedSteps = 0;

    @Column(name = "failed_steps", nullable = false)
    public Integer failedSteps = 0;

    // 性能指标
    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "avg_step_duration_ms")
    public Long avgStepDurationMs;

    // 快照信息（引用）
    @Column(name = "snapshot_seq")
    public Long snapshotSeq;

    @Column(name = "has_snapshot")
    public Boolean hasSnapshot = false;

    // 业务元数据（可扩展）
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String metadata;

    // 版本控制（乐观锁）
    @Version
    @Column(nullable = false)
    public Integer version = 0;

    /**
     * 查找指定 workflow 的查询视图
     *
     * @param workflowId workflow 唯一标识符
     * @return 查询视图，如果不存在则返回 empty
     */
    public static Optional<WorkflowQueryViewEntity> findByWorkflowId(UUID workflowId) {
        return find("workflowId", workflowId).firstResultOptional();
    }

    /**
     * 获取或创建查询视图
     *
     * @param workflowId workflow 唯一标识符
     * @return 查询视图实体
     */
    public static WorkflowQueryViewEntity getOrCreate(UUID workflowId) {
        return findByWorkflowId(workflowId).orElseGet(() -> {
            WorkflowQueryViewEntity view = new WorkflowQueryViewEntity();
            view.workflowId = workflowId;
            view.status = "READY";
            view.createdAt = Instant.now();
            view.updatedAt = view.createdAt;
            view.totalEvents = 0L;
            view.totalSteps = 0;
            view.completedSteps = 0;
            view.failedSteps = 0;
            view.hasSnapshot = false;
            return view;
        });
    }
}
