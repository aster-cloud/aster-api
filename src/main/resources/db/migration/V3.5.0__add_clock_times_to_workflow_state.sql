-- Phase 3.6: Workflow Replay 实战化 - 持久化 deterministic clock
-- 作者: Claude Code
-- 日期: 2025-11-11
-- 目的: 支持 workflow 确定性时间重放，用于审计合规场景

-- 添加 clock_times JSONB 列存储时间决策序列
-- 结构: { "recordedTimes": ["2025-01-10T08:00:00Z", ...], "replayIndex": 0, "replayMode": false, "version": 1 }
ALTER TABLE workflow_state
ADD COLUMN clock_times JSONB DEFAULT NULL;

-- 添加列注释说明用途
COMMENT ON COLUMN workflow_state.clock_times IS
'ReplayDeterministicClock 时间决策序列,包含 recordedTimes/replayIndex/replayMode,用于确定性重放';
