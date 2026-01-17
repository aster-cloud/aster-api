-- Phase 3.7: 扩展 anomaly_reports 表 - 支持异常响应自动化
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 添加状态管理和验证结果字段，实现检测→验证→处置闭环

-- 添加状态管理字段
ALTER TABLE anomaly_reports
ADD COLUMN status VARCHAR(32) DEFAULT 'PENDING' NOT NULL;

-- 添加指派和解决字段
ALTER TABLE anomaly_reports
ADD COLUMN assigned_to VARCHAR(255);

ALTER TABLE anomaly_reports
ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE anomaly_reports
ADD COLUMN resolution_notes TEXT;

-- 添加验证结果字段（JSONB 存储 VerificationResult）
ALTER TABLE anomaly_reports
ADD COLUMN verification_result JSONB;

-- 联合索引：按状态和严重度查询待处理的高危异常
CREATE INDEX idx_anomaly_reports_status_severity
ON anomaly_reports(status, severity);

-- 稀疏索引：仅索引已指派的异常（WHERE 条件过滤 NULL）
CREATE INDEX idx_anomaly_reports_assigned_to
ON anomaly_reports(assigned_to)
WHERE assigned_to IS NOT NULL;

-- GIN 索引：支持 JSONB 字段查询（如查询 replaySucceeded=true 的异常）
CREATE INDEX idx_anomaly_reports_verification_result
ON anomaly_reports USING GIN(verification_result);

-- 注释
COMMENT ON COLUMN anomaly_reports.status IS '异常状态：PENDING, VERIFYING, VERIFIED, RESOLVED, DISMISSED';
COMMENT ON COLUMN anomaly_reports.assigned_to IS '指派给的用户 ID 或团队';
COMMENT ON COLUMN anomaly_reports.resolved_at IS '异常解决时间';
COMMENT ON COLUMN anomaly_reports.resolution_notes IS '解决说明或处置备注';
COMMENT ON COLUMN anomaly_reports.verification_result IS 'Replay 验证结果 JSON：{replaySucceeded, anomalyReproduced, workflowId, replayedAt, originalDurationMs, replayDurationMs}';
