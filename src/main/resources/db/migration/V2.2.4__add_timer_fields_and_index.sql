-- Add missing fields to workflow_timers table
ALTER TABLE workflow_timers
ADD COLUMN IF NOT EXISTS step_id VARCHAR(128),
ADD COLUMN IF NOT EXISTS interval_millis BIGINT,
ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

-- Create index for efficient timer polling
-- Partial index: only index PENDING timers
CREATE INDEX IF NOT EXISTS idx_timer_execution
ON workflow_timers(fire_at, status)
WHERE status = 'PENDING';

-- Create index for workflow_id lookups
CREATE INDEX IF NOT EXISTS idx_timer_workflow
ON workflow_timers(workflow_id);

COMMENT ON COLUMN workflow_timers.step_id IS 'Workflow step identifier to resume after timer fires';
COMMENT ON COLUMN workflow_timers.interval_millis IS 'Interval in milliseconds for periodic timers';
COMMENT ON COLUMN workflow_timers.retry_count IS 'Number of failed execution attempts';
COMMENT ON INDEX idx_timer_execution IS 'Partial index for efficient polling of pending timers';
