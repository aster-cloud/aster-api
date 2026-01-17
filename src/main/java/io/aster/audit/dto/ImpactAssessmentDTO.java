package io.aster.audit.dto;

/**
 * 影响评估 DTO（Phase 3.2）
 *
 * 用于返回策略版本回滚的影响评估信息。
 */
public class ImpactAssessmentDTO {

    /** 策略版本 ID */
    public Long versionId;

    /** 活跃 workflow 数量 */
    public int activeCount;

    /** 已完成 workflow 数量 */
    public int completedCount;

    /** 失败 workflow 数量 */
    public int failedCount;

    /** 总计数（综合指标）*/
    public int totalCount;

    /** 风险等级 */
    public String riskLevel;
}
