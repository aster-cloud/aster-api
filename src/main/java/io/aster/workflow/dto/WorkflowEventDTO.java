package io.aster.workflow.dto;

import java.time.Instant;

/**
 * Workflow 事件 DTO
 *
 * 用于 REST API 响应。
 */
public class WorkflowEventDTO {
    public long sequence;
    public String workflowId;
    public String eventType;
    public Object payload;
    public Instant occurredAt;

    public WorkflowEventDTO() {
    }

    public WorkflowEventDTO(long sequence, String workflowId, String eventType, Object payload, Instant occurredAt) {
        this.sequence = sequence;
        this.workflowId = workflowId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }
}
