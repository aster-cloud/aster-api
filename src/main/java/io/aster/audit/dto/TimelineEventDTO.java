package io.aster.audit.dto;

import java.time.Instant;

/**
 * 时间线事件 DTO（Phase 3.2）
 *
 * 用于返回策略版本使用的时间线数据。
 */
public class TimelineEventDTO {

    /** 时间戳 */
    public Instant timestamp;

    /** 事件类型 */
    public String eventType;

    /** Workflow ID */
    public String workflowId;

    /** 事件计数 */
    public int count;
}
