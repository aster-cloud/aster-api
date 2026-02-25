-- 添加调度计数器，用于检测 workflow 死循环调度
ALTER TABLE workflow_state ADD COLUMN IF NOT EXISTS schedule_count INTEGER NOT NULL DEFAULT 0;

-- 将长期卡在 RUNNING/READY 状态的 workflow 标记为 FAILED
UPDATE workflow_state
SET status = 'FAILED',
    error_message = 'Terminated: exceeded maximum schedule attempts (stuck workflow)',
    completed_at = NOW(),
    duration_ms = EXTRACT(EPOCH FROM (NOW() - COALESCE(started_at, created_at))) * 1000
WHERE status IN ('READY', 'RUNNING')
  AND created_at < NOW() - INTERVAL '1 hour';
