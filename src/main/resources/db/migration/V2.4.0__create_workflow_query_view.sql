-- Phase 2.4: CQRS 查询投影表
-- 作者: Claude Code
-- 日期: 2025-11-17
-- 目的: 分离读写操作，优化查询性能

-- 创建查询视图表（Read Model）
CREATE TABLE workflow_query_view (
    workflow_id UUID PRIMARY KEY,

    -- 基本状态信息
    status VARCHAR(32) NOT NULL,
    policy_version_id BIGINT,

    -- 时间追踪
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- 执行结果与错误
    result JSONB,
    error_message TEXT,

    -- 统计信息（预计算）
    total_events BIGINT NOT NULL DEFAULT 0,
    total_steps INTEGER NOT NULL DEFAULT 0,
    completed_steps INTEGER NOT NULL DEFAULT 0,
    failed_steps INTEGER NOT NULL DEFAULT 0,

    -- 性能指标
    duration_ms BIGINT,
    avg_step_duration_ms BIGINT,

    -- 快照信息（引用）
    snapshot_seq BIGINT,
    has_snapshot BOOLEAN DEFAULT FALSE,

    -- 业务元数据（可扩展）
    metadata JSONB,

    -- 版本控制（乐观锁）
    version INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT chk_query_status CHECK (status IN (
        'READY',
        'RUNNING',
        'COMPLETED',
        'FAILED',
        'COMPENSATING',
        'COMPENSATED',
        'COMPENSATION_FAILED',
        'TERMINATED',
        'PAUSED'
    ))
);

-- 索引优化
-- 1. 状态查询（按状态过滤）
CREATE INDEX idx_query_view_status ON workflow_query_view(status);

-- 2. 版本查询（按策略版本分组统计）
CREATE INDEX idx_query_view_policy_version ON workflow_query_view(policy_version_id, status);

-- 3. 时间范围查询（按创建/完成时间过滤）
CREATE INDEX idx_query_view_created_at ON workflow_query_view(created_at DESC);
CREATE INDEX idx_query_view_completed_at ON workflow_query_view(completed_at DESC) WHERE completed_at IS NOT NULL;

-- 4. 性能分析查询（按执行时长排序）
CREATE INDEX idx_query_view_duration ON workflow_query_view(duration_ms DESC) WHERE duration_ms IS NOT NULL;

-- 5. 复合索引：状态 + 完成时间（高频查询）
CREATE INDEX idx_query_view_status_completed ON workflow_query_view(status, completed_at DESC);

-- 6. Snapshot 相关查询
CREATE INDEX idx_query_view_snapshot ON workflow_query_view(has_snapshot, snapshot_seq) WHERE has_snapshot = true;

-- 注释说明
COMMENT ON TABLE workflow_query_view IS 'CQRS 查询视图表：优化读操作性能的预计算模型';
COMMENT ON COLUMN workflow_query_view.workflow_id IS 'Workflow 唯一标识符';
COMMENT ON COLUMN workflow_query_view.status IS 'Workflow 当前状态';
COMMENT ON COLUMN workflow_query_view.policy_version_id IS '关联的策略版本 ID';
COMMENT ON COLUMN workflow_query_view.total_events IS '事件总数（预计算）';
COMMENT ON COLUMN workflow_query_view.total_steps IS '步骤总数';
COMMENT ON COLUMN workflow_query_view.completed_steps IS '已完成步骤数';
COMMENT ON COLUMN workflow_query_view.failed_steps IS '失败步骤数';
COMMENT ON COLUMN workflow_query_view.duration_ms IS '总执行时长（毫秒）';
COMMENT ON COLUMN workflow_query_view.avg_step_duration_ms IS '平均步骤执行时长（毫秒）';
COMMENT ON COLUMN workflow_query_view.has_snapshot IS '是否有快照（加速查询）';
COMMENT ON COLUMN workflow_query_view.snapshot_seq IS '最新快照序列号';
COMMENT ON COLUMN workflow_query_view.metadata IS '扩展元数据（JSON格式）';
COMMENT ON COLUMN workflow_query_view.version IS '版本号（乐观锁，防止并发更新冲突）';
