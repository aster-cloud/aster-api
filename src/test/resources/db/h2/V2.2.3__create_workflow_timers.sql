-- H2 版本：Workflow Timers 表

CREATE TABLE workflow_timers (
    timer_id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    fire_at TIMESTAMP NOT NULL,
    payload CLOB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    CONSTRAINT fk_workflow_timers_workflow
        FOREIGN KEY (workflow_id)
        REFERENCES workflow_state (workflow_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_workflow_timers_fire ON workflow_timers (fire_at, status);
CREATE INDEX idx_workflow_timers_workflow ON workflow_timers (workflow_id);
