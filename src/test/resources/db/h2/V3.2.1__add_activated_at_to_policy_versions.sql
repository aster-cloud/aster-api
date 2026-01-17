-- Phase 3.2: 为 policy_versions 添加激活时间字段
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 支持版本激活时间追踪，用于审计时间线分析

-- 添加 activated_at 列
ALTER TABLE policy_versions
ADD COLUMN activated_at TIMESTAMP WITH TIME ZONE;

-- 创建索引加速激活时间查询
-- 场景: 按激活时间范围查询策略版本
CREATE INDEX idx_policy_versions_activated_at
ON policy_versions(activated_at);
