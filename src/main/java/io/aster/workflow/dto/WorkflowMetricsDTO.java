package io.aster.workflow.dto;

/**
 * Workflow 指标 DTO
 *
 * 用于 REST API 响应。
 */
public class WorkflowMetricsDTO {
    public long readyCount;
    public long runningCount;
    public long completedCount;
    public long failedCount;
    public long compensatingCount;
    public long compensatedCount;
    public long compensationFailedCount;

    public WorkflowMetricsDTO() {
    }

    public WorkflowMetricsDTO(long readyCount, long runningCount, long completedCount,
                             long failedCount, long compensatingCount, long compensatedCount,
                             long compensationFailedCount) {
        this.readyCount = readyCount;
        this.runningCount = runningCount;
        this.completedCount = completedCount;
        this.failedCount = failedCount;
        this.compensatingCount = compensatingCount;
        this.compensatedCount = compensatedCount;
        this.compensationFailedCount = compensationFailedCount;
    }
}
