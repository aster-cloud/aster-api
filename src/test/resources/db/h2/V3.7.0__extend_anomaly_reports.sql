-- Phase 3.7: 扩展 anomaly_reports 表 - 支持异常响应自动化
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 添加状态管理和验证结果字段，实现检测→验证→处置闭环
-- H2 兼容版本：TEXT→VARCHAR, JSONB→JSON, 移除 COMMENT ON

-- 添加状态管理字段
ALTER TABLE anomaly_reports
ADD COLUMN status VARCHAR(32) DEFAULT 'PENDING' NOT NULL;

-- 添加指派和解决字段
ALTER TABLE anomaly_reports
ADD COLUMN assigned_to VARCHAR(255);

ALTER TABLE anomaly_reports
ADD COLUMN resolved_at TIMESTAMP;

ALTER TABLE anomaly_reports
ADD COLUMN resolution_notes VARCHAR(10000);  -- H2: TEXT→VARCHAR(10000)

-- 添加验证结果字段（H2: JSONB→JSON）
ALTER TABLE anomaly_reports
ADD COLUMN verification_result JSON;

-- 联合索引：按状态和严重度查询待处理的高危异常
CREATE INDEX idx_anomaly_reports_status_severity
ON anomaly_reports(status, severity);

-- 稀疏索引：H2 不完全支持 WHERE 条件，但语法兼容
CREATE INDEX idx_anomaly_reports_assigned_to
ON anomaly_reports(assigned_to);

-- H2 不支持 GIN 索引，跳过 verification_result 索引
-- CREATE INDEX idx_anomaly_reports_verification_result
-- ON anomaly_reports USING GIN(verification_result);

-- H2 不支持 COMMENT ON，使用内联注释代替
-- status: 异常状态：PENDING, VERIFYING, VERIFIED, RESOLVED, DISMISSED
-- assigned_to: 指派给的用户 ID 或团队
-- resolved_at: 异常解决时间
-- resolution_notes: 解决说明或处置备注
-- verification_result: Replay 验证结果 JSON：{replaySucceeded, anomalyReproduced, workflowId, replayedAt, originalDurationMs, replayDurationMs}
