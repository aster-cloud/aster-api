package io.aster.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 版本对比 DTO（Phase 3.3）
 *
 * 对比两个策略版本的性能指标，支持版本回滚决策。
 *
 * Bug Fix: 修复 avgDurationMs 为 null 时前端显示 NaN 的问题
 * - versionAAvgDurationMs/versionBAvgDurationMs: 当无完成记录时返回 0.0（而非 null）
 * - hasVersionAAvgDuration/hasVersionBAvgDuration: 标识是否有有效数据
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VersionComparisonDTO {

    /** 版本 A ID */
    public Long versionAId;

    /** 版本 B ID */
    public Long versionBId;

    /** 版本 A workflow 数量 */
    public int versionAWorkflowCount;

    /** 版本 B workflow 数量 */
    public int versionBWorkflowCount;

    /** 版本 A 成功率（0-100）*/
    public double versionASuccessRate;

    /** 版本 B 成功率（0-100）*/
    public double versionBSuccessRate;

    /**
     * 版本 A 平均执行时长（毫秒）
     * Bug Fix: 当无完成记录时返回 0.0（而非 null），避免前端 NaN
     */
    public double versionAAvgDurationMs;

    /**
     * 版本 B 平均执行时长（毫秒）
     * Bug Fix: 当无完成记录时返回 0.0（而非 null），避免前端 NaN
     */
    public double versionBAvgDurationMs;

    /** 版本 A 是否有有效的平均执行时长数据 */
    public boolean hasVersionAAvgDuration;

    /** 版本 B 是否有有效的平均执行时长数据 */
    public boolean hasVersionBAvgDuration;

    /** 胜出版本（A, B, TIE）*/
    public String winner;

    /** 建议措施 */
    public String recommendation;
}
