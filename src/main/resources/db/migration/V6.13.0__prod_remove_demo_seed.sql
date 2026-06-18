-- Phase 6.13: 生产环境清理 demo 种子数据（prod-safe 补偿迁移）
-- 作者: Claude Code
-- 日期: 2026-06-18
-- 目的: 把 #55 prod-seed gating 从「修改已应用的 V5.2.0」改为「新增前向补偿迁移」。
--
-- 背景：V5.2.0__seed_demo_workflow_data.sql 是已应用的版本化迁移。#55(PR #56)
-- 直接在该文件每条 INSERT 上加 ${asterDemoSeedEnabled} placeholder 来阻止 prod 注入
-- demo 数据——但这**改变了 V5.2.0 的 SHA-256**，而生产 Flyway 实际开了
-- validate-on-migrate（migrate-at-start 校验已应用迁移的 checksum），导致
-- FlywayValidateException → 所有新 pod CrashLoopBackOff。
--
-- 正确做法：V5.2.0 已还原为原始内容（checksum 复位，与生产 schema_history 一致），
-- gating 改由本前向迁移完成——
--   - prod（asterDemoSeedEnabled=false）：删除 V5.2.0 可能注入的 demo 种子行。
--   - dev/test（asterDemoSeedEnabled=true）：no-op，保留 demo 数据。
--
-- 迁移不可变铁律：本文件一旦应用即不可再改内容；后续需求请再加新迁移。

-- prod 守护：placeholder=false 时下面的 DELETE 的 WHERE 恒为假（一行不删）；
-- dev/test=true 时同样恒为假（demo 数据要保留）——咦，二者都不删？
-- 不：prod 要删、dev 要留，所以条件应是「仅当 NOT enabled（即 prod）才删」。
-- Flyway placeholder 是文本替换：`NOT ${asterDemoSeedEnabled}` → prod 为 `NOT false`
-- = true（删），dev/test 为 `NOT true` = false（不删）。

-- 1) policy_versions：V5.2.0 注入的两条 demo 策略
DELETE FROM policy_versions
WHERE (NOT ${asterDemoSeedEnabled})
  AND policy_id IN ('demo.loan.approval', 'demo.fraud.detection');

-- 2) workflow_state：V5.2.0 注入的 demo-tenant 工作流
DELETE FROM workflow_state
WHERE (NOT ${asterDemoSeedEnabled})
  AND tenant_id = 'demo-tenant';

-- 诊断：报告本次删除影响（仅日志，无副作用）。
DO $$
DECLARE
  pv_count INT;
  ws_count INT;
BEGIN
  SELECT count(*) INTO pv_count FROM policy_versions
    WHERE policy_id IN ('demo.loan.approval', 'demo.fraud.detection');
  SELECT count(*) INTO ws_count FROM workflow_state WHERE tenant_id = 'demo-tenant';
  RAISE NOTICE 'V6.13.0 prod demo-seed cleanup: remaining demo policy_versions=%, demo workflow_state=%',
    pv_count, ws_count;
END $$;
