-- Phase 3.8: 添加 sample_workflow_id 到 anomaly_reports 表
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 存储代表性失败 workflow 实例 ID，用于 replay 验证

-- 添加代表性样本 workflow ID 字段
ALTER TABLE anomaly_reports
ADD COLUMN sample_workflow_id UUID;

-- 稀疏索引：仅索引包含 sample_workflow_id 的异常（WHERE 条件过滤 NULL）
-- 注意：H2 不支持部分索引，但 PostgreSQL 生产环境会使用 WHERE 子句优化
CREATE INDEX idx_anomaly_reports_sample_workflow
ON anomaly_reports(sample_workflow_id)
WHERE sample_workflow_id IS NOT NULL;

-- 注释
COMMENT ON COLUMN anomaly_reports.sample_workflow_id IS '代表性失败 workflow 实例，用于 replay 验证';
