-- 添加重试元数据列
ALTER TABLE workflow_events ADD COLUMN IF NOT EXISTS attempt_number INT DEFAULT 1;
ALTER TABLE workflow_events ADD COLUMN IF NOT EXISTS backoff_delay_ms BIGINT;
ALTER TABLE workflow_events ADD COLUMN IF NOT EXISTS failure_reason TEXT;

-- 创建索引以支持按 attempt 查询
CREATE INDEX IF NOT EXISTS idx_workflow_events_attempt
ON workflow_events(workflow_id, attempt_number);
