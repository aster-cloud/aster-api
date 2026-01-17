-- 扩展 policy_versions 表以支持动态策略加载
ALTER TABLE policy_versions
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS core_json JSONB,
    ADD COLUMN IF NOT EXISTS compiler_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS locale VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_tenant_module_function
    ON policy_versions(tenant_id, module_name, function_name);

-- 回滚
DROP INDEX IF EXISTS idx_tenant_module_function;

ALTER TABLE policy_versions
    DROP COLUMN IF EXISTS locale,
    DROP COLUMN IF EXISTS compiler_version,
    DROP COLUMN IF EXISTS core_json,
    DROP COLUMN IF EXISTS source_hash,
    DROP COLUMN IF EXISTS tenant_id;
