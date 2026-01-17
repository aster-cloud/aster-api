-- 策略版本表
-- 支持不可变部署：每次策略更新都创建新版本，旧版本标记为非活跃
CREATE TABLE policy_versions (
    id BIGSERIAL PRIMARY KEY,
    policy_id VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    module_name VARCHAR(200) NOT NULL,
    function_name VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100),
    notes TEXT,
    CONSTRAINT uk_policy_version UNIQUE (policy_id, version)
);

-- 索引：快速查找活跃版本
CREATE INDEX idx_policy_id_active ON policy_versions (policy_id, active);

-- 索引：快速查找特定版本
CREATE INDEX idx_policy_id_version ON policy_versions (policy_id, version);

-- 注释
COMMENT ON TABLE policy_versions IS '策略版本表，支持不可变部署和版本回滚';
COMMENT ON COLUMN policy_versions.policy_id IS '策略唯一标识符（业务ID，跨版本不变）';
COMMENT ON COLUMN policy_versions.version IS '版本号（使用 timestamp 确保唯一性）';
COMMENT ON COLUMN policy_versions.module_name IS '策略模块名称（如 aster.finance.loan）';
COMMENT ON COLUMN policy_versions.function_name IS '策略函数名称（如 evaluateLoanEligibility）';
COMMENT ON COLUMN policy_versions.content IS '策略内容（Aster CNL 代码或 JSON 配置）';
COMMENT ON COLUMN policy_versions.active IS '是否为活跃版本（每个 policyId 只有一个活跃版本）';
COMMENT ON COLUMN policy_versions.created_at IS '创建时间';
COMMENT ON COLUMN policy_versions.created_by IS '创建者';
COMMENT ON COLUMN policy_versions.notes IS '备注信息（版本变更说明）';
