package io.aster.audit.dto;

import java.time.Instant;

/**
 * 时间序列数据点 DTO（Phase 3.3）
 *
 * 用于前端时间序列图表绘制（支持多指标展示）。
 */
public class TimeSeriesDataPointDTO {

    /** 时间戳 */
    public Instant timestamp;

    /** 指标名称（如 "successRate", "avgDuration", "totalCount"）*/
    public String metricName;

    /** 指标值 */
    public double value;

    /** 显示标签（用于图例展示）*/
    public String label;
}
