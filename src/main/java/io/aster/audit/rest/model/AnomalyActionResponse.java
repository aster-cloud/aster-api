package io.aster.audit.rest.model;

/**
 * 异常响应动作结果（Phase 3.7）
 *
 * 用于返回异常动作提交或执行的结果。
 *
 * @param actionId   动作 ID
 * @param actionType 动作类型（VERIFY_REPLAY, AUTO_ROLLBACK）
 * @param message    操作消息（如 "验证动作已提交到队列"）
 */
public record AnomalyActionResponse(
    Long actionId,
    String actionType,
    String message
) {
}
