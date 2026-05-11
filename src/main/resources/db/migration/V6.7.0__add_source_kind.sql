-- 添加 source_kind 列用于北极星指标 WAADR
-- WAADR = 每周被业务专家采纳的 AI 草稿规则数
-- 详见 aster-deploy/docs/pm/02-north-star-metric.md
--
-- 取值：
--   manual            人工从零开始撰写
--   ai_draft          AI 直接生成、未经修改即保存
--   ai_draft_edited   AI 生成后被人工编辑后保存（NSM 计入此类）
--   imported          从外部系统导入（如旧策略迁移）

ALTER TABLE policy_versions
    ADD COLUMN source_kind VARCHAR(32) NOT NULL DEFAULT 'manual';

-- 用于按 source_kind 与时间范围聚合的物化视图查询
CREATE INDEX idx_policy_versions_source_kind_status
    ON policy_versions (source_kind, status);

-- 历史数据回填：迁移前已有数据全部标记为 manual（保守且不影响 NSM 统计）
-- 由 DEFAULT 自动完成，无需 UPDATE

COMMENT ON COLUMN policy_versions.source_kind IS
    '版本来源：manual / ai_draft / ai_draft_edited / imported (用于 NSM-WAADR 统计)';
