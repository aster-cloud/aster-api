-- Phase 2.2: Workflow Timers 定时器表
-- 支持 durable timers，用于延迟执行、超时检测和定时任务

CREATE TABLE workflow_timers (
    timer_id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    fire_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_workflow_timers_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES workflow_state (workflow_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_timer_status CHECK (status IN (
        'PENDING',
        'FIRED',
        'CANCELLED'
    ))
);

-- 触发时间 + 状态的复合索引，用于定时器轮询查询
-- 使用部分索引仅索引待触发的定时器
CREATE INDEX idx_workflow_timers_fire
    ON workflow_timers (fire_at, status)
    WHERE status = 'PENDING';

-- workflow_id 索引，用于查询特定 workflow 的所有定时器
CREATE INDEX idx_workflow_timers_workflow
    ON workflow_timers (workflow_id);

-- 注释说明
COMMENT ON TABLE workflow_timers IS 'Workflow 定时器表，支持 durable timers 和延迟执行';
COMMENT ON COLUMN workflow_timers.timer_id IS '定时器唯一标识符';
COMMENT ON COLUMN workflow_timers.workflow_id IS '关联的 workflow ID';
COMMENT ON COLUMN workflow_timers.fire_at IS '定时器触发时间';
COMMENT ON COLUMN workflow_timers.payload IS '定时器负载数据（JSON 格式）';
COMMENT ON COLUMN workflow_timers.status IS '定时器状态（PENDING, FIRED, CANCELLED）';
COMMENT ON COLUMN workflow_timers.created_at IS '定时器创建时间';
