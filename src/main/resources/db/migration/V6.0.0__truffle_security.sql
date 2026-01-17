-- Truffle 安全架构 Phase 1: 基础安全层数据库扩展
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 扩展策略版本元数据
ALTER TABLE policy_versions
    ADD COLUMN source_hash CHAR(64),
    ADD COLUMN prev_hash CHAR(64),
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN release_note TEXT,
    ADD COLUMN deprecated_at TIMESTAMPTZ,
    ADD COLUMN deprecated_by VARCHAR(100),
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by VARCHAR(100);

CREATE INDEX idx_policy_versions_status ON policy_versions(status);
CREATE INDEX idx_policy_versions_is_default ON policy_versions(is_default);

COMMENT ON COLUMN policy_versions.source_hash IS '策略源码 SHA256 哈希（十六进制）';
COMMENT ON COLUMN policy_versions.prev_hash IS '前序版本的源码哈希，用于构建链式信任';
COMMENT ON COLUMN policy_versions.status IS '版本状态：DRAFT/SUBMITTED/APPROVED/REJECTED/DEPRECATED/ARCHIVED';
COMMENT ON COLUMN policy_versions.is_default IS '标记当前租户默认版本';
COMMENT ON COLUMN policy_versions.release_note IS '变更说明 / Release Note';
COMMENT ON COLUMN policy_versions.deprecated_at IS '版本弃用时间戳';
COMMENT ON COLUMN policy_versions.archived_at IS '版本归档时间戳';

-- 审批记录表
CREATE TABLE policy_approval (
    id BIGSERIAL PRIMARY KEY,
    policy_version_id BIGINT NOT NULL REFERENCES policy_versions(id) ON DELETE CASCADE,
    step SMALLINT NOT NULL,
    approver_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    comment TEXT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_version_id, step)
);

CREATE INDEX idx_policy_approval_version ON policy_approval(policy_version_id);
CREATE INDEX idx_policy_approval_status ON policy_approval(status);

-- Nonce 防重放表
CREATE TABLE used_nonce (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, nonce)
);

CREATE INDEX idx_used_nonce_expires_at ON used_nonce(expires_at);
CREATE INDEX idx_used_nonce_request_hash ON used_nonce(tenant_id, request_hash);

-- 安全事件日志表
CREATE TABLE security_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255),
    policy_version_id BIGINT,
    event_type VARCHAR(50) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    payload JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_security_event_version FOREIGN KEY (policy_version_id) REFERENCES policy_versions(id) ON DELETE SET NULL
);

CREATE INDEX idx_security_event_type ON security_event(event_type);
CREATE INDEX idx_security_event_tenant ON security_event(tenant_id);
CREATE INDEX idx_security_event_handled ON security_event(handled);

-- 历史数据回填
UPDATE policy_versions
SET source_hash = encode(digest(content::bytea, 'sha256'), 'hex')
WHERE source_hash IS NULL;

WITH ordered AS (
    SELECT
        id,
        policy_id,
        version,
        LAG(source_hash) OVER (PARTITION BY policy_id ORDER BY version) AS prev_hash_value
    FROM policy_versions
)
UPDATE policy_versions p
SET prev_hash = ordered.prev_hash_value
FROM ordered
WHERE ordered.id = p.id
  AND p.prev_hash IS NULL;

UPDATE policy_versions
SET status = CASE WHEN active THEN 'APPROVED' ELSE 'ARCHIVED' END,
    is_default = COALESCE(active, FALSE),
    release_note = COALESCE(release_note, notes)
WHERE status = 'DRAFT';

ALTER TABLE policy_versions
    ALTER COLUMN source_hash SET NOT NULL;
