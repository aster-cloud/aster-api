package io.aster.audit.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 异常检测报告 DTO（Phase 3.3）
 *
 * 自动识别需要关注的异常情况（高失败率、僵尸版本、性能劣化）。
 */
public class AnomalyReportDTO {

    /** 异常类型（HIGH_FAILURE_RATE, ZOMBIE_VERSION, PERFORMANCE_DEGRADATION）*/
    public String anomalyType;

    /** 策略版本 ID */
    public Long versionId;

    /** 策略 ID（如 "aster.finance.riskScore"）*/
    public String policyId;

    /** 严重程度（CRITICAL, WARNING, INFO）*/
    public String severity;

    /** 异常描述 */
    public String description;

    /** 指标值（如失败率 0.45 表示 45%）*/
    public double metricValue;

    /** 阈值（触发异常的阈值）*/
    public double threshold;

    /** 检测时间 */
    public Instant detectedAt;

    /** 建议措施 */
    public String recommendation;

    /** 代表性失败 workflow 实例 ID（Phase 3.8）*/
    public UUID sampleWorkflowId;

    /** 租户 ID（Phase 4.3 - 用于后台调度器写入时设置租户隔离）*/
    public String tenantId;
}
