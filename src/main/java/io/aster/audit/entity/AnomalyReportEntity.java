package io.aster.audit.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 异常检测报告实体（Phase 3.4 / Phase 3.7 扩展）
 *
 * 用于存储定时任务生成的异常检测结果，支持异步化异常检测。
 * Phase 3.7 扩展：添加状态管理、指派、验证结果字段，支持异常响应自动化闭环。
 */
@RegisterForReflection
@Entity
@Table(name = "anomaly_reports")
public class AnomalyReportEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "anomaly_type", nullable = false, length = 64)
    public String anomalyType;

    @Column(name = "version_id")
    public Long versionId;

    @Column(name = "policy_id", nullable = false, length = 255)
    public String policyId;

    @Column(name = "metric_value")
    public Double metricValue;

    @Column(name = "threshold")
    public Double threshold;

    @Column(name = "severity", nullable = false, length = 16)
    public String severity;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    public String recommendation;

    @Column(name = "detected_at", nullable = false)
    public Instant detectedAt;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    // ==================== Phase 3.7 扩展字段 ====================

    /**
     * 异常状态（Phase 3.7）
     * 状态机：PENDING → VERIFYING → VERIFIED → RESOLVED/DISMISSED
     */
    @Column(name = "status", nullable = false, length = 32)
    public String status = "PENDING";

    /**
     * 指派给的用户 ID 或团队（Phase 3.7）
     */
    @Column(name = "assigned_to", length = 255)
    public String assignedTo;

    /**
     * 异常解决时间（Phase 3.7）
     */
    @Column(name = "resolved_at")
    public Instant resolvedAt;

    /**
     * 解决说明或处置备注（Phase 3.7）
     */
    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    public String resolutionNotes;

    /**
     * Replay 验证结果（Phase 3.7）
     * JSONB 结构：{ "replaySucceeded": true, "anomalyReproduced": false, "workflowId": "wf-123",
     *              "replayedAt": "2025-11-11T10:00:00Z", "originalDurationMs": 150, "replayDurationMs": 148 }
     * 用于记录 Workflow Replay 验证异常的结果
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_result", columnDefinition = "jsonb")
    public String verificationResult;

    // ==================== Phase 3.8 扩展字段 ====================

    /**
     * 代表性失败 workflow 实例 ID（Phase 3.8）
     * 从异常检测阶段捕获，用于 Replay 验证时提供具体的 workflow 实例
     */
    @Column(name = "sample_workflow_id")
    public UUID sampleWorkflowId;

    /**
     * 租户ID（Phase 4.3）
     * 用于多租户数据隔离，确保租户只能访问自己的异常报告
     */
    @Column(name = "tenant_id", nullable = false, length = 255)
    public String tenantId;

    // ==================== Panache Active Record 查询方法 ====================

    /**
     * 查询最近 N 天的异常报告（带租户过滤）
     *
     * @param tenantId 租户ID
     * @param days 天数
     * @return 异常报告列表，按检测时间降序排列
     */
    public static List<AnomalyReportEntity> findRecent(String tenantId, int days) {
        return find("tenant_id = ?1 AND detected_at >= ?2 ORDER BY detected_at DESC",
            tenantId, Instant.now().minus(days, ChronoUnit.DAYS))
            .list();
    }

    /**
     * 查询最近 N 天的异常报告（不带租户过滤，仅用于系统级管理）
     *
     * @param days 天数
     * @return 异常报告列表，按检测时间降序排列
     * @deprecated 使用 findRecent(String tenantId, int days) 替代
     */
    @Deprecated
    public static List<AnomalyReportEntity> findRecent(int days) {
        return find("detected_at >= ?1 ORDER BY detected_at DESC",
            Instant.now().minus(days, ChronoUnit.DAYS))
            .list();
    }

    /**
     * 按异常类型查询（带租户过滤）
     *
     * @param tenantId 租户ID
     * @param type 异常类型（HIGH_FAILURE_RATE, ZOMBIE_VERSION, PERFORMANCE_DEGRADATION）
     * @param days 天数
     * @return 指定类型的异常报告列表
     */
    public static List<AnomalyReportEntity> findByType(String tenantId, String type, int days) {
        return find("tenant_id = ?1 AND anomaly_type = ?2 AND detected_at >= ?3 ORDER BY detected_at DESC",
            tenantId, type, Instant.now().minus(days, ChronoUnit.DAYS))
            .list();
    }

    /**
     * 统计 CRITICAL 严重程度的异常数量（带租户过滤）
     *
     * @param tenantId 租户ID
     * @param days 天数
     * @return CRITICAL 异常数量
     */
    public static long countCritical(String tenantId, int days) {
        return count("tenant_id = ?1 AND severity = 'CRITICAL' AND detected_at >= ?2",
            tenantId, Instant.now().minus(days, ChronoUnit.DAYS));
    }

    /**
     * 删除超过 N 天的历史记录（定时清理任务）
     *
     * @param days 保留天数
     * @return 删除的记录数
     */
    public static long deleteOlderThan(int days) {
        return delete("detected_at < ?1", Instant.now().minus(days, ChronoUnit.DAYS));
    }

    /**
     * 按版本 ID 查询异常历史（带租户过滤）
     *
     * @param tenantId  租户ID
     * @param versionId 策略版本 ID
     * @param days      天数
     * @return 指定版本的异常报告列表
     */
    public static List<AnomalyReportEntity> findByVersion(String tenantId, Long versionId, int days) {
        return find("tenant_id = ?1 AND version_id = ?2 AND detected_at >= ?3 ORDER BY detected_at DESC",
            tenantId, versionId, Instant.now().minus(days, ChronoUnit.DAYS))
            .list();
    }
}
