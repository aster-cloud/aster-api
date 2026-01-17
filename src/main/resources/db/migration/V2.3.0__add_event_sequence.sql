-- 添加自增序列列，确保并发安全
ALTER TABLE workflow_events
ADD COLUMN IF NOT EXISTS seq BIGSERIAL;

-- 创建索引加速查询
CREATE INDEX IF NOT EXISTS idx_workflow_events_seq ON workflow_events(seq);

-- 注释说明
COMMENT ON COLUMN workflow_events.seq IS 'Auto-incrementing sequence number for event ordering';
