-- Phase 3.4: 创建异常检测报告表
-- 作者: Claude Code
-- 日期: 2025-11-10
-- 目的: 支持异步化异常检测，提升 API 响应速度

CREATE TABLE anomaly_reports (
    id BIGSERIAL PRIMARY KEY,
    anomaly_type VARCHAR(64) NOT NULL,
    version_id BIGINT,
    policy_id VARCHAR(255) NOT NULL,
    metric_value DOUBLE PRECISION,
    threshold DOUBLE PRECISION,
    severity VARCHAR(16) NOT NULL,
    description TEXT,
    recommendation TEXT,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 索引：按检测时间降序查询（最新的异常优先）
CREATE INDEX idx_anomaly_reports_detected_at
ON anomaly_reports(detected_at DESC);

-- 索引：按版本 ID 查询（分析特定版本的异常历史）
CREATE INDEX idx_anomaly_reports_version_id
ON anomaly_reports(version_id);

-- 索引：按异常类型过滤（如仅查询 HIGH_FAILURE_RATE）
CREATE INDEX idx_anomaly_reports_anomaly_type
ON anomaly_reports(anomaly_type);

-- 外键：关联策略版本（可选，允许 NULL 用于僵尸版本检测）
ALTER TABLE anomaly_reports
ADD CONSTRAINT fk_anomaly_reports_version_id
FOREIGN KEY (version_id) REFERENCES policy_versions(id)
ON DELETE CASCADE;

-- 注释
COMMENT ON TABLE anomaly_reports IS '异常检测报告表，用于存储定时任务生成的异常检测结果';
COMMENT ON COLUMN anomaly_reports.anomaly_type IS '异常类型：HIGH_FAILURE_RATE, ZOMBIE_VERSION, PERFORMANCE_DEGRADATION';
COMMENT ON COLUMN anomaly_reports.detected_at IS '异常发生时间（实际检测的时间点）';
COMMENT ON COLUMN anomaly_reports.created_at IS '记录创建时间（写入数据库的时间）';
