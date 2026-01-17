-- Phase 3.8 回滚脚本
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 回滚 V3.8.0 迁移（如果需要）
--
-- 警告: 此脚本仅用于紧急回滚，会删除 sample_workflow_id 列及其数据
-- 使用前请确保已备份数据

-- 1. 删除索引
DROP INDEX IF EXISTS idx_anomaly_reports_sample_workflow;

-- 2. 删除列（会丢失所有 sample_workflow_id 数据）
ALTER TABLE anomaly_reports
DROP COLUMN IF EXISTS sample_workflow_id;

-- 回滚完成后的验证查询
-- SELECT column_name FROM information_schema.columns
-- WHERE table_name = 'anomaly_reports' AND column_name = 'sample_workflow_id';
-- 应该返回 0 行
