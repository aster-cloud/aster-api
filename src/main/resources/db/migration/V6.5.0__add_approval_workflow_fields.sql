-- Phase 1.4: 为策略版本新增审批工作流字段
-- 作者: Codex
-- 日期: 2026-01-15
-- 目的: 支持草稿→审批→激活状态机，记录提交、审批、拒绝与激活操作人信息

ALTER TABLE policy_versions
    ADD COLUMN submitted_by VARCHAR(100),
    ADD COLUMN submitted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN approved_by VARCHAR(100),
    ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN activated_by VARCHAR(100),
    ADD COLUMN rejected_by VARCHAR(100),
    ADD COLUMN rejected_at TIMESTAMP WITH TIME ZONE;
