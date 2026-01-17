package io.aster.audit.rest.model;

import jakarta.validation.constraints.NotNull;

/**
 * 异常 Replay 验证请求（Phase 3.7）
 *
 * 用于手动触发异常的 Workflow Replay 验证。
 *
 * @param workflowId 要重放的 workflow ID
 * @param dryRun     是否为试运行模式（true=只验证不记录结果）
 */
public record AnomalyReplayRequest(
    @NotNull(message = "workflowId 不能为空")
    String workflowId,

    Boolean dryRun
) {
}
