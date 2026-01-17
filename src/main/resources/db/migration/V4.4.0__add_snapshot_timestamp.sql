-- V4.4.0: 添加 last_snapshot_at 字段支持基于时间间隔的快照
-- P0-2 criterion 4: 快照间隔可配置（时间/事件数）
--
-- 设计:
-- 1. last_snapshot_at 记录最后一次快照的时间戳
-- 2. 配合 workflow.snapshot.time-interval-minutes 配置实现时间触发
-- 3. 快照触发条件: 事件间隔 OR 时间间隔（两者满足其一即触发）

ALTER TABLE workflow_state
    ADD COLUMN last_snapshot_at TIMESTAMPTZ;

-- 为现有记录设置初始值（使用 created_at 作为默认值）
UPDATE workflow_state
SET last_snapshot_at = created_at
WHERE last_snapshot_at IS NULL;

-- 添加索引以支持基于时间的快照查询（可选，用于未来的后台调度器）
CREATE INDEX idx_workflow_state_last_snapshot_at
    ON workflow_state (last_snapshot_at)
    WHERE status IN ('RUNNING', 'READY');

-- 注释
COMMENT ON COLUMN workflow_state.last_snapshot_at IS '最后一次快照的时间戳，用于基于时间间隔的快照触发';
