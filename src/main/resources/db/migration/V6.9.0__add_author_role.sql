-- v1.2: WAADR 视图按业务角色过滤
-- 详见 aster-deploy/docs/pm/02-north-star-metric.md（NSM 精确定义中的 role 过滤）
--
-- 背景：
--   v1.1 时 WAADR 视图只能按 tenant_id 聚合，因为 author 角色信息在 aster-cloud 而 aster-api 没有 users 表
--   v1.2 改造：aster-cloud 在调用 aster-api 写策略版本时，把作者角色通过 X-User-Role 头传过来，
--   aster-api 持久化到 policy_versions.author_role，让 WAADR 视图按业务角色过滤
--
-- 取值约定（与 PM 02-NSM 文档对齐）：
--   business_expert      业务专家
--   compliance_officer   合规官
--   risk_analyst         风控分析师
--   engineer / admin     工程 / 管理（不计入 NSM）
--   unknown              旧数据 / 未传角色（不计入 NSM）

ALTER TABLE policy_versions
    ADD COLUMN author_role VARCHAR(64) NOT NULL DEFAULT 'unknown';

COMMENT ON COLUMN policy_versions.author_role IS
    '作者业务角色，用于 WAADR 北极星指标精确过滤';

-- 重建 WAADR 物化视图：在 source_kind + status + activated_at 过滤之上，
-- 再加 author_role IN (business_expert, compliance_officer, risk_analyst) 过滤
DROP MATERIALIZED VIEW IF EXISTS pm_weekly_waadr CASCADE;

CREATE MATERIALIZED VIEW pm_weekly_waadr AS
SELECT
    date_trunc('week', activated_at) AS week,
    tenant_id,
    author_role,
    COUNT(*) AS waadr
FROM policy_versions
WHERE source_kind = 'ai_draft_edited'
  AND status = 'APPROVED'
  AND activated_at IS NOT NULL
  AND author_role IN ('business_expert', 'compliance_officer', 'risk_analyst')
GROUP BY 1, 2, 3;

CREATE UNIQUE INDEX uk_pm_weekly_waadr_week_tenant_role
    ON pm_weekly_waadr (week, tenant_id, author_role);

-- 优化索引：让 WAADR 候选行的过滤更快
DROP INDEX IF EXISTS idx_policy_versions_waadr_source;
CREATE INDEX idx_policy_versions_waadr_source
    ON policy_versions (source_kind, status, activated_at, tenant_id, author_role)
    WHERE source_kind = 'ai_draft_edited'
      AND status = 'APPROVED'
      AND activated_at IS NOT NULL;

COMMENT ON MATERIALIZED VIEW pm_weekly_waadr IS
    'WAADR 北极星指标周聚合视图（v1.2 加入 author_role 过滤）。仅业务角色（business_expert / compliance_officer / risk_analyst）发布的 ai_draft_edited 版本计入。';
