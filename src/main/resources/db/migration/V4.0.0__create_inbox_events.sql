-- Inbox 模式：去重已处理事件，防止重复执行
CREATE TABLE inbox_events (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引优化查询
CREATE INDEX idx_inbox_events_tenant ON inbox_events(tenant_id);
CREATE INDEX idx_inbox_events_processed_at ON inbox_events(processed_at);

-- 表和列注释
COMMENT ON TABLE inbox_events IS 'Inbox 模式：去重已处理事件，防止重复执行';
COMMENT ON COLUMN inbox_events.idempotency_key IS '幂等性键，全局唯一';
COMMENT ON COLUMN inbox_events.event_type IS '事件类型（如 POLICY_CREATE, ANOMALY_UPDATE）';
COMMENT ON COLUMN inbox_events.tenant_id IS '租户ID，用于多租户隔离';
COMMENT ON COLUMN inbox_events.processed_at IS '事件处理时间';
COMMENT ON COLUMN inbox_events.payload IS '可选的事件 payload（JSONB格式，用于调试或审计）';
