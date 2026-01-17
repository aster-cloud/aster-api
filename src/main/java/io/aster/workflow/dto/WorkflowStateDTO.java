package io.aster.workflow.dto;

import java.time.Instant;

/**
 * Workflow 状态 DTO
 *
 * 用于 REST API 响应。
 */
public class WorkflowStateDTO {
    public String workflowId;
    public String status;
    public long lastEventSeq;
    public Object result;
    public Object snapshot;
    public Long snapshotSeq;
    public Instant createdAt;
    public Instant updatedAt;

    public WorkflowStateDTO() {
    }

    public WorkflowStateDTO(String workflowId, String status, long lastEventSeq, Object result,
                           Object snapshot, Long snapshotSeq, Instant createdAt, Instant updatedAt) {
        this.workflowId = workflowId;
        this.status = status;
        this.lastEventSeq = lastEventSeq;
        this.result = result;
        this.snapshot = snapshot;
        this.snapshotSeq = snapshotSeq;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
