package io.aster.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 版本使用统计 DTO（Phase 3.3）
 *
 * 提供按时间粒度聚合的版本使用量、成功率、失败率和平均执行时间。
 *
 * Bug Fix: 修复 avgDurationMs 为 null 时前端显示 NaN 的问题
 * - avgDurationMs: 当无完成记录时返回 0.0（而非 null），避免前端 NaN
 * - hasAvgDuration: 标识 avgDurationMs 是否有有效数据（true=有完成记录，false=无完成记录）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VersionUsageStatsDTO {

    /** 策略版本 ID */
    public Long versionId;

    /** 时间桶（聚合时间粒度的起点）*/
    public Instant timeBucket;

    /** 总 workflow 数量 */
    public int totalCount;

    /** 已完成 workflow 数量 */
    public int completedCount;

    /** 失败 workflow 数量 */
    public int failedCount;

    /** 运行中 workflow 数量 */
    public int runningCount;

    /** 成功率（0-100）*/
    public double successRate;

    /**
     * 平均执行时长（毫秒）
     *
     * Bug Fix: 当无完成记录时返回 0.0（而非 null），避免前端计算时产生 NaN。
     * 前端应结合 hasAvgDuration 字段判断是否显示此值。
     */
    public double avgDurationMs;

    /**
     * 是否有有效的平均执行时长数据
     *
     * - true: 有已完成的 workflow，avgDurationMs 是有效的平均值
     * - false: 无已完成的 workflow，avgDurationMs 为 0.0（占位值，不应显示）
     *
     * 前端显示逻辑：
     * - hasAvgDuration=true 时，显示 avgDurationMs + "ms"
     * - hasAvgDuration=false 时，显示 "N/A" 或 "-"
     */
    public boolean hasAvgDuration;

    /** 租户 ID（用于多租户过滤）*/
    public String tenantId;
}
