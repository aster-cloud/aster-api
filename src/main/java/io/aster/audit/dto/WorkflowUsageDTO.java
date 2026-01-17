package io.aster.audit.dto;

import java.time.Instant;

/**
 * Workflow 使用信息 DTO（Phase 3.2）
 *
 * 用于返回使用特定策略版本的 workflow 信息。
 */
public class WorkflowUsageDTO {

    /** Workflow ID */
    public String workflowId;

    /** Workflow 状态 */
    public String status;

    /** 策略激活时间 */
    public Instant policyActivatedAt;

    /** 最后事件序号 */
    public Long lastEventSeq;

    /** 租户 ID（用于多租户过滤）*/
    public String tenantId;
}
