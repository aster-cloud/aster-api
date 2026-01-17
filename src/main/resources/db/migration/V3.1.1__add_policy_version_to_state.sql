-- Phase 3.1: 为 workflow_state 添加策略版本追踪
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 快速查询使用特定版本的 workflow，支持版本影响评估

-- 添加 policy_version_id 列
ALTER TABLE workflow_state
ADD COLUMN policy_version_id BIGINT;

-- 添加 policy_activated_at 列
ALTER TABLE workflow_state
ADD COLUMN policy_activated_at TIMESTAMP WITH TIME ZONE;

-- 添加外键约束
ALTER TABLE workflow_state
ADD CONSTRAINT fk_workflow_state_policy_version
FOREIGN KEY (policy_version_id)
REFERENCES policy_versions(id)
ON DELETE SET NULL;  -- 如果策略版本被删除，将状态的版本引用设为 NULL

-- 创建索引加速版本过滤查询
-- 场景1: 查询使用特定版本的所有 workflow
-- 场景2: 评估版本回滚的影响范围（有多少运行中的 workflow 在使用该版本）
-- 注意：H2 不支持部分索引，但 PostgreSQL 生产环境会使用 WHERE 子句优化
CREATE INDEX idx_workflow_state_policy_version
ON workflow_state(policy_version_id, status);

-- 创建复合索引加速审计查询
-- 场景: 生成版本使用时间线报告
CREATE INDEX idx_workflow_state_policy_activated
ON workflow_state(policy_activated_at, policy_version_id);
