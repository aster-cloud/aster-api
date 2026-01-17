-- Phase 3.7: 创建异常响应动作队列表 - Outbox 模式
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 解耦异常检测与响应执行，支持异步动作处理

CREATE TABLE anomaly_actions (
    id BIGSERIAL PRIMARY KEY,
    anomaly_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'PENDING' NOT NULL,
    payload JSONB,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

-- 外键：关联异常报告，级联删除
ALTER TABLE anomaly_actions
ADD CONSTRAINT fk_anomaly_actions_anomaly_id
FOREIGN KEY (anomaly_id) REFERENCES anomaly_reports(id)
ON DELETE CASCADE;

-- 部分索引：仅索引待处理和执行中的动作（高效队列查询）
CREATE INDEX idx_anomaly_actions_status_created
ON anomaly_actions(status, created_at)
WHERE status IN ('PENDING', 'RUNNING');

-- 普通索引：按异常 ID 查询该异常的所有动作历史
CREATE INDEX idx_anomaly_actions_anomaly_id
ON anomaly_actions(anomaly_id);

-- 注释
COMMENT ON TABLE anomaly_actions IS '异常响应动作队列表，采用 outbox 模式解耦检测与执行';
COMMENT ON COLUMN anomaly_actions.action_type IS '动作类型：VERIFY_REPLAY, AUTO_ROLLBACK';
COMMENT ON COLUMN anomaly_actions.status IS '动作状态：PENDING, RUNNING, DONE, FAILED';
COMMENT ON COLUMN anomaly_actions.payload IS '动作参数 JSON，如 {workflowId, targetVersion}';
COMMENT ON COLUMN anomaly_actions.error_message IS '执行失败时的错误信息';
COMMENT ON COLUMN anomaly_actions.created_at IS '动作创建时间（提交到队列）';
COMMENT ON COLUMN anomaly_actions.started_at IS '开始执行时间';
COMMENT ON COLUMN anomaly_actions.completed_at IS '完成时间（成功或失败）';
