-- Phase 2.2: Workflow Events 事件表
-- 使用事件溯源模式存储 workflow 执行历史，支持状态重放和审计

-- 创建分区父表
CREATE TABLE workflow_events (
    id BIGSERIAL,
    workflow_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(255),
    PRIMARY KEY (workflow_id, sequence, id),
    UNIQUE (workflow_id, sequence),
    UNIQUE (workflow_id, idempotency_key)
) PARTITION BY HASH (workflow_id);

-- 创建分区表（16 个分区，支持高并发写入）
CREATE TABLE workflow_events_p0 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 0);

CREATE TABLE workflow_events_p1 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 1);

CREATE TABLE workflow_events_p2 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 2);

CREATE TABLE workflow_events_p3 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 3);

CREATE TABLE workflow_events_p4 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 4);

CREATE TABLE workflow_events_p5 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 5);

CREATE TABLE workflow_events_p6 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 6);

CREATE TABLE workflow_events_p7 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 7);

CREATE TABLE workflow_events_p8 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 8);

CREATE TABLE workflow_events_p9 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 9);

CREATE TABLE workflow_events_p10 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 10);

CREATE TABLE workflow_events_p11 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 11);

CREATE TABLE workflow_events_p12 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 12);

CREATE TABLE workflow_events_p13 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 13);

CREATE TABLE workflow_events_p14 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 14);

CREATE TABLE workflow_events_p15 PARTITION OF workflow_events
    FOR VALUES WITH (MODULUS 16, REMAINDER 15);

-- 创建索引（自动应用到所有分区）
-- workflow_id + sequence 已经通过 UNIQUE 约束自动创建索引
-- 时间索引用于按时间范围查询（审计、监控）
CREATE INDEX idx_workflow_events_occurred ON workflow_events (occurred_at);

-- 注释说明
COMMENT ON TABLE workflow_events IS 'Workflow 事件溯源表，记录所有 workflow 执行事件';
COMMENT ON COLUMN workflow_events.id IS '全局唯一事件 ID';
COMMENT ON COLUMN workflow_events.workflow_id IS 'Workflow 唯一标识符';
COMMENT ON COLUMN workflow_events.sequence IS 'Workflow 内事件序列号（单调递增）';
COMMENT ON COLUMN workflow_events.event_type IS '事件类型（WorkflowStarted, StepCompleted, etc.）';
COMMENT ON COLUMN workflow_events.payload IS '事件负载数据（JSON 格式）';
COMMENT ON COLUMN workflow_events.occurred_at IS '事件发生时间';
COMMENT ON COLUMN workflow_events.idempotency_key IS '幂等性键，防止重复执行';
