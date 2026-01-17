-- H2 版本：Workflow State 表

CREATE TABLE workflow_state (
    workflow_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    result CLOB,
    snapshot CLOB,
    snapshot_seq BIGINT,
    lock_owner VARCHAR(64),
    lock_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP()
);

CREATE INDEX idx_workflow_state_status_lock ON workflow_state (status, lock_expires_at);
CREATE INDEX idx_workflow_state_updated ON workflow_state (updated_at);
