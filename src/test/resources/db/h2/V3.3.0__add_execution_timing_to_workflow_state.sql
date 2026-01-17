-- Phase 3.3: 为 workflow_state 添加执行时间追踪
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 支持性能统计、异常检测和版本对比分析

-- 添加 workflow 开始时间
ALTER TABLE workflow_state
ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;

-- 添加 workflow 完成时间
ALTER TABLE workflow_state
ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

-- 添加执行时长（毫秒）- 冗余字段，用于查询优化
ALTER TABLE workflow_state
ADD COLUMN duration_ms BIGINT;

-- 添加错误信息（仅 status=FAILED 时有值）
ALTER TABLE workflow_state
ADD COLUMN error_message TEXT;

-- 创建索引加速时间范围查询
-- 场景: 按时间粒度聚合版本使用统计（支持 hour/day/week/month）
CREATE INDEX idx_workflow_state_started_at
ON workflow_state(started_at);

-- 创建复合索引加速性能统计查询
-- 场景: 按状态和完成时间统计平均执行时长
CREATE INDEX idx_workflow_state_completed_status
ON workflow_state(completed_at, status);
