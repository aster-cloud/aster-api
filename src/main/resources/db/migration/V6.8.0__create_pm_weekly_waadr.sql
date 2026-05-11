-- 北极星指标（WAADR）物化视图
-- 详见 aster-deploy/docs/pm/02-north-star-metric.md
--
-- WAADR = Weekly Adopted AI-Drafted Rules
--   = 每周被业务专家采纳的 AI 草稿规则数
--
-- 计算口径（aster-api 实际可用字段）：
--   - source_kind = 'ai_draft_edited'：AI 生成后被人工编辑的版本
--   - status = 'APPROVED' AND activated_at IS NOT NULL：已上线版本
--   - 按 tenant_id 聚合（aster-api 无 users 表，业务角色精度交给 Mixpanel author_role 属性）

-- 1. 部分索引：仅对 WAADR 候选行建索引，加速 REFRESH 与点查
CREATE INDEX IF NOT EXISTS idx_policy_versions_waadr_source
    ON policy_versions (source_kind, status, activated_at, tenant_id)
    WHERE source_kind = 'ai_draft_edited'
      AND status = 'APPROVED'
      AND activated_at IS NOT NULL;

-- 2. 物化视图本体
CREATE MATERIALIZED VIEW pm_weekly_waadr AS
SELECT
    date_trunc('week', activated_at) AS week,
    tenant_id,
    COUNT(*) AS waadr
FROM policy_versions
WHERE source_kind = 'ai_draft_edited'
  AND status = 'APPROVED'
  AND activated_at IS NOT NULL
GROUP BY 1, 2;

-- 3. UNIQUE 索引是 REFRESH MATERIALIZED VIEW CONCURRENTLY 的硬性要求
CREATE UNIQUE INDEX uk_pm_weekly_waadr_week_tenant
    ON pm_weekly_waadr (week, tenant_id);

COMMENT ON MATERIALIZED VIEW pm_weekly_waadr IS
    'WAADR 北极星指标周聚合视图。由 WaadrRefreshScheduler 每周一 00:30 CONCURRENTLY 刷新。';
