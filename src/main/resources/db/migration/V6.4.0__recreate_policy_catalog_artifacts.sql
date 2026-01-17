-- 重新创建 policy_catalog 与 policy_artifacts，并补齐 policy_versions 的动态字段，修复先前脚本中意外回滚导致的缺失
ALTER TABLE policy_versions
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64) DEFAULT '0000000000000000000000000000000000000000000000000000000000000000' NOT NULL,
    ADD COLUMN IF NOT EXISTS core_json JSONB,
    ADD COLUMN IF NOT EXISTS compiler_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS locale VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_tenant_module_function
    ON policy_versions(tenant_id, module_name, function_name);

CREATE TABLE IF NOT EXISTS policy_catalog
(
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(100)  NOT NULL,
    module_name         VARCHAR(200)  NOT NULL,
    function_name       VARCHAR(200)  NOT NULL,
    domain              VARCHAR(50),
    tags                JSONB,
    default_version_id  BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    CONSTRAINT policy_catalog_unique_per_tenant UNIQUE (tenant_id, module_name, function_name),
    CONSTRAINT policy_catalog_default_version_fk FOREIGN KEY (default_version_id) REFERENCES policy_versions (id)
);

CREATE INDEX IF NOT EXISTS idx_catalog_tenant
    ON policy_catalog (tenant_id);

CREATE TABLE IF NOT EXISTS policy_artifacts
(
    id                UUID PRIMARY KEY,
    policy_version_id BIGINT       NOT NULL,
    artifact_type     VARCHAR(50)  NOT NULL,
    content           BYTEA,
    content_sha256    VARCHAR(64),
    compiler_opts     JSONB,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT policy_artifacts_version_fk FOREIGN KEY (policy_version_id) REFERENCES policy_versions (id)
);

CREATE INDEX IF NOT EXISTS idx_artifacts_version
    ON policy_artifacts (policy_version_id, artifact_type);
