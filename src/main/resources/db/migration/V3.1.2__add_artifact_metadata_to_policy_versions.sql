-- Phase 3.1: 为 policy_versions 添加编译产物元数据
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 追踪策略版本对应的编译产物，支持审计还原和合规验证

-- 添加 artifact_sha256 列
ALTER TABLE policy_versions
ADD COLUMN artifact_sha256 CHAR(64);

-- 添加 artifact_uri 列
ALTER TABLE policy_versions
ADD COLUMN artifact_uri TEXT;

-- 添加 runtime_build 列
ALTER TABLE policy_versions
ADD COLUMN runtime_build VARCHAR(50);

-- 创建索引加速 artifact 查询
-- 场景: 根据 SHA256 反查策略版本（用于审计追溯）
-- 注意：H2 不支持部分索引，但 PostgreSQL 生产环境会使用 WHERE 子句优化
CREATE INDEX idx_policy_versions_artifact_sha256
ON policy_versions(artifact_sha256);

-- 创建索引加速 runtime 版本查询
-- 场景: 查询使用特定 runtime 版本的所有策略（用于兼容性验证）
CREATE INDEX idx_policy_versions_runtime_build
ON policy_versions(runtime_build);
