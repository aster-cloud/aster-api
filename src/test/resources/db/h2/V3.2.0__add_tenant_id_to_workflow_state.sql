-- Phase 3.2: 为 workflow_state 添加租户ID字段
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 支持多租户场景的 workflow 过滤

-- 添加 tenant_id 列
ALTER TABLE workflow_state
ADD COLUMN tenant_id VARCHAR(64);

-- 创建索引加速租户过滤查询
-- 场景: 查询特定租户的 workflow（用于多租户审计）
CREATE INDEX idx_workflow_state_tenant
ON workflow_state(tenant_id);
