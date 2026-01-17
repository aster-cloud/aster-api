package io.aster.audit.outbox;

/**
 * 通用 Outbox 事件状态
 */
public enum OutboxStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}

