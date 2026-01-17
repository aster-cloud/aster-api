package io.aster.policy.event;

/**
 * 审计事件类型定义。
 */
public enum EventType {
    POLICY_EVALUATION,
    POLICY_ROLLBACK,
    POLICY_CREATED,
    ORDER_SUBMITTED,
    ORDER_STATUS_QUERIED,

    // Phase 3.7: 异常响应自动化事件
    ANOMALY_VERIFICATION,     // 异常 Replay 验证
    ANOMALY_STATUS_CHANGE,    // 异常状态变更
    ANOMALY_AUTO_ROLLBACK     // 异常自动回滚
}
