-- Phase 3.1: 为 workflow_events 添加策略版本追踪
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 支持审计追溯每次 workflow 执行使用的策略版本

-- 添加 policy_version_id 列（允许 NULL 以兼容历史数据）
ALTER TABLE workflow_events
ADD COLUMN policy_version_id BIGINT;

-- 添加外键约束
ALTER TABLE workflow_events
ADD CONSTRAINT fk_workflow_events_policy_version
FOREIGN KEY (policy_version_id)
REFERENCES policy_versions(id)
ON DELETE SET NULL;  -- 如果策略版本被删除，将事件的版本引用设为 NULL

-- 创建索引加速审计查询
-- 场景1: 查询使用特定版本的所有事件
-- 场景2: 按时间范围生成版本使用报告
-- 注意：H2 不支持部分索引，但 PostgreSQL 生产环境会使用 WHERE 子句优化
CREATE INDEX idx_workflow_events_policy_version
ON workflow_events(policy_version_id, occurred_at);
