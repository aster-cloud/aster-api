-- Phase 2.2: Workflow State 状态表
-- 记录 workflow 当前状态，支持快速查询和调度

CREATE TABLE workflow_state (
    workflow_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    result JSONB,
    snapshot JSONB,
    snapshot_seq BIGINT,
    lock_owner VARCHAR(64),
    lock_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_status CHECK (status IN (
        'READY',
        'RUNNING',
        'COMPLETED',
        'FAILED',
        'COMPENSATING',
        'COMPENSATED',
        'COMPENSATION_FAILED',
        'TERMINATED'
    ))
);

-- 状态 + 锁过期时间的复合索引，用于调度器查询就绪的 workflow
-- 使用部分索引（WHERE 子句）减少索引大小，仅索引需要调度的状态
CREATE INDEX idx_workflow_state_status_lock
    ON workflow_state (status, lock_expires_at)
    WHERE status IN ('READY', 'RUNNING');

-- 更新时间索引，用于监控和清理
CREATE INDEX idx_workflow_state_updated
    ON workflow_state (updated_at);

-- 注释说明
COMMENT ON TABLE workflow_state IS 'Workflow 状态表，记录当前执行状态和结果';
COMMENT ON COLUMN workflow_state.workflow_id IS 'Workflow 唯一标识符';
COMMENT ON COLUMN workflow_state.status IS 'Workflow 状态（READY, RUNNING, COMPLETED, etc.）';
COMMENT ON COLUMN workflow_state.last_event_seq IS '最后处理的事件序列号';
COMMENT ON COLUMN workflow_state.result IS '执行结果（仅在 COMPLETED 或 FAILED 时有值）';
COMMENT ON COLUMN workflow_state.snapshot IS '状态快照（JSON 格式），用于优化重放';
COMMENT ON COLUMN workflow_state.snapshot_seq IS '快照对应的事件序列号';
COMMENT ON COLUMN workflow_state.lock_owner IS '当前持有锁的 worker 标识';
COMMENT ON COLUMN workflow_state.lock_expires_at IS '锁过期时间（用于死锁恢复）';
COMMENT ON COLUMN workflow_state.created_at IS 'Workflow 创建时间';
COMMENT ON COLUMN workflow_state.updated_at IS '最后更新时间';
