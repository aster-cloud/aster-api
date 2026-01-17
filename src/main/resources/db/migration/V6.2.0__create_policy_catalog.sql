-- 创建 policy_catalog 表用于存储策略目录元数据
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

-- 回滚
DROP INDEX IF EXISTS idx_catalog_tenant;
DROP TABLE IF EXISTS policy_catalog;
