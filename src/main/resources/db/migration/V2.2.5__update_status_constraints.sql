-- Update workflow_state status constraint to include PAUSED
ALTER TABLE workflow_state
DROP CONSTRAINT IF EXISTS chk_status;

ALTER TABLE workflow_state
ADD CONSTRAINT chk_status CHECK (status IN (
    'READY',
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'COMPENSATING',
    'COMPENSATED',
    'PAUSED'
));

-- Update workflow_timers status constraint to include EXECUTING, COMPLETED, FAILED
ALTER TABLE workflow_timers
DROP CONSTRAINT IF EXISTS chk_timer_status;

ALTER TABLE workflow_timers
ADD CONSTRAINT chk_timer_status CHECK (status IN (
    'PENDING',
    'EXECUTING',
    'COMPLETED',
    'FAILED',
    'FIRED',
    'CANCELLED'
));

COMMENT ON CONSTRAINT chk_status ON workflow_state IS 'Allowed workflow states including PAUSED for timer support';
COMMENT ON CONSTRAINT chk_timer_status ON workflow_timers IS 'Allowed timer states including EXECUTING, COMPLETED, FAILED';
