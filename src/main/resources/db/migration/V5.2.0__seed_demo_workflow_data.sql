-- Phase 5.2: Demo 环境示例数据初始化
-- 作者: Claude Code
-- 日期: 2026-01-10
-- 目的: 为 demo 环境提供示例 workflow 数据，解决统计页面空值和 NaN 显示问题
-- 注意: 仅在 demo/dev 环境执行，生产环境应跳过或回滚

-- ============================================================================
-- 1. 创建 Demo 策略版本（如果不存在）
-- ============================================================================

-- Demo 贷款审批策略 v1
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, created_at, created_by, notes)
SELECT 'demo.loan.approval', 1736000000000, 'demo.loan', 'evaluateLoanApproval',
       '当 申请人.信用评分 >= 700 且 申请人.负债收入比 < 0.4 时
  批准贷款申请
  设置 利率 为 基准利率 - 0.5%
否则 当 申请人.信用评分 >= 600 时
  人工审核
否则
  拒绝贷款申请',
       true, NOW() - INTERVAL '30 days', 'system', 'Demo 贷款审批策略 - 初始版本'
WHERE NOT EXISTS (SELECT 1 FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736000000000);

-- Demo 贷款审批策略 v2（更新版）
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, created_at, created_by, notes)
SELECT 'demo.loan.approval', 1736100000000, 'demo.loan', 'evaluateLoanApproval',
       '当 申请人.信用评分 >= 680 且 申请人.负债收入比 < 0.45 时
  批准贷款申请
  设置 利率 为 基准利率 - 0.3%
否则 当 申请人.信用评分 >= 580 时
  人工审核
否则
  拒绝贷款申请',
       false, NOW() - INTERVAL '15 days', 'system', 'Demo 贷款审批策略 - 降低门槛'
WHERE NOT EXISTS (SELECT 1 FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736100000000);

-- Demo 欺诈检测策略
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, created_at, created_by, notes)
SELECT 'demo.fraud.detection', 1736000000000, 'demo.fraud', 'detectFraud',
       '当 交易.金额 > 10000 且 交易.位置 != 用户.常用位置 时
  标记为 高风险
  触发 人工复核
否则 当 交易.频率 > 每小时10次 时
  标记为 中风险
  发送 验证短信
否则
  放行交易',
       true, NOW() - INTERVAL '20 days', 'system', 'Demo 欺诈检测策略'
WHERE NOT EXISTS (SELECT 1 FROM policy_versions WHERE policy_id = 'demo.fraud.detection' AND version = 1736000000000);

-- ============================================================================
-- 2. 获取策略版本 ID（用于后续插入）
-- ============================================================================

-- 使用 CTE 获取 Demo 策略版本 ID
WITH demo_versions AS (
    SELECT id, policy_id, version
    FROM policy_versions
    WHERE policy_id IN ('demo.loan.approval', 'demo.fraud.detection')
),
loan_v1 AS (SELECT id FROM demo_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736000000000),
loan_v2 AS (SELECT id FROM demo_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736100000000),
fraud_v1 AS (SELECT id FROM demo_versions WHERE policy_id = 'demo.fraud.detection' AND version = 1736000000000)

-- ============================================================================
-- 3. 创建 Demo Workflow 数据
-- ============================================================================

-- 使用 generate_series 创建批量测试数据
INSERT INTO workflow_state (
    workflow_id,
    status,
    last_event_seq,
    result,
    created_at,
    updated_at,
    policy_version_id,
    policy_activated_at,
    tenant_id,
    started_at,
    completed_at,
    duration_ms,
    error_message
)
SELECT
    gen_random_uuid() AS workflow_id,
    CASE
        WHEN i % 10 = 0 THEN 'FAILED'
        WHEN i % 15 = 0 THEN 'RUNNING'
        ELSE 'COMPLETED'
    END AS status,
    i AS last_event_seq,
    CASE
        WHEN i % 10 = 0 THEN '{"error": "信用评分不足"}'::jsonb
        WHEN i % 15 = 0 THEN NULL
        ELSE '{"approved": true, "rate": 4.5}'::jsonb
    END AS result,
    NOW() - (random() * INTERVAL '30 days') AS created_at,
    NOW() - (random() * INTERVAL '1 day') AS updated_at,
    (SELECT id FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736000000000 LIMIT 1) AS policy_version_id,
    NOW() - (random() * INTERVAL '30 days') AS policy_activated_at,
    'demo-tenant' AS tenant_id,
    NOW() - (random() * INTERVAL '30 days') AS started_at,
    CASE
        WHEN i % 15 = 0 THEN NULL  -- RUNNING 状态无完成时间
        ELSE NOW() - (random() * INTERVAL '1 day')
    END AS completed_at,
    CASE
        WHEN i % 15 = 0 THEN NULL  -- RUNNING 状态无执行时长
        ELSE (50 + random() * 450)::bigint  -- 50-500ms
    END AS duration_ms,
    CASE
        WHEN i % 10 = 0 THEN '申请人信用评分 ' || (400 + random() * 200)::int || ' 低于最低要求 600'
        ELSE NULL
    END AS error_message
FROM generate_series(1, 50) AS i
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_state ws
    WHERE ws.tenant_id = 'demo-tenant'
    AND ws.policy_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736000000000 LIMIT 1)
    LIMIT 1
);

-- 为贷款策略 v2 创建数据（较新版本，较少数据）
INSERT INTO workflow_state (
    workflow_id,
    status,
    last_event_seq,
    result,
    created_at,
    updated_at,
    policy_version_id,
    policy_activated_at,
    tenant_id,
    started_at,
    completed_at,
    duration_ms,
    error_message
)
SELECT
    gen_random_uuid() AS workflow_id,
    CASE
        WHEN i % 12 = 0 THEN 'FAILED'
        WHEN i % 20 = 0 THEN 'RUNNING'
        ELSE 'COMPLETED'
    END AS status,
    i AS last_event_seq,
    CASE
        WHEN i % 12 = 0 THEN '{"error": "负债收入比过高"}'::jsonb
        WHEN i % 20 = 0 THEN NULL
        ELSE '{"approved": true, "rate": 4.2}'::jsonb
    END AS result,
    NOW() - (random() * INTERVAL '15 days') AS created_at,
    NOW() - (random() * INTERVAL '1 day') AS updated_at,
    (SELECT id FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736100000000 LIMIT 1) AS policy_version_id,
    NOW() - (random() * INTERVAL '15 days') AS policy_activated_at,
    'demo-tenant' AS tenant_id,
    NOW() - (random() * INTERVAL '15 days') AS started_at,
    CASE
        WHEN i % 20 = 0 THEN NULL
        ELSE NOW() - (random() * INTERVAL '1 day')
    END AS completed_at,
    CASE
        WHEN i % 20 = 0 THEN NULL
        ELSE (40 + random() * 360)::bigint  -- 40-400ms (新版本更快)
    END AS duration_ms,
    CASE
        WHEN i % 12 = 0 THEN '申请人负债收入比 ' || (0.45 + random() * 0.2)::numeric(3,2) || ' 超过阈值 0.45'
        ELSE NULL
    END AS error_message
FROM generate_series(1, 30) AS i
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_state ws
    WHERE ws.tenant_id = 'demo-tenant'
    AND ws.policy_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'demo.loan.approval' AND version = 1736100000000 LIMIT 1)
    LIMIT 1
);

-- 为欺诈检测策略创建数据
INSERT INTO workflow_state (
    workflow_id,
    status,
    last_event_seq,
    result,
    created_at,
    updated_at,
    policy_version_id,
    policy_activated_at,
    tenant_id,
    started_at,
    completed_at,
    duration_ms,
    error_message
)
SELECT
    gen_random_uuid() AS workflow_id,
    CASE
        WHEN i % 8 = 0 THEN 'FAILED'
        WHEN i % 25 = 0 THEN 'RUNNING'
        ELSE 'COMPLETED'
    END AS status,
    i AS last_event_seq,
    CASE
        WHEN i % 8 = 0 THEN '{"error": "检测服务超时"}'::jsonb
        WHEN i % 25 = 0 THEN NULL
        ELSE '{"riskLevel": "LOW", "action": "ALLOW"}'::jsonb
    END AS result,
    NOW() - (random() * INTERVAL '20 days') AS created_at,
    NOW() - (random() * INTERVAL '1 day') AS updated_at,
    (SELECT id FROM policy_versions WHERE policy_id = 'demo.fraud.detection' AND version = 1736000000000 LIMIT 1) AS policy_version_id,
    NOW() - (random() * INTERVAL '20 days') AS policy_activated_at,
    'demo-tenant' AS tenant_id,
    NOW() - (random() * INTERVAL '20 days') AS started_at,
    CASE
        WHEN i % 25 = 0 THEN NULL
        ELSE NOW() - (random() * INTERVAL '1 day')
    END AS completed_at,
    CASE
        WHEN i % 25 = 0 THEN NULL
        ELSE (30 + random() * 270)::bigint  -- 30-300ms (欺诈检测更快)
    END AS duration_ms,
    CASE
        WHEN i % 8 = 0 THEN '外部欺诈检测服务响应超时（' || (5000 + random() * 5000)::int || 'ms）'
        ELSE NULL
    END AS error_message
FROM generate_series(1, 40) AS i
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_state ws
    WHERE ws.tenant_id = 'demo-tenant'
    AND ws.policy_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'demo.fraud.detection' AND version = 1736000000000 LIMIT 1)
    LIMIT 1
);

-- ============================================================================
-- 4. 验证数据
-- ============================================================================

-- 输出统计信息（仅用于调试，不影响迁移）
DO $$
DECLARE
    total_workflows INT;
    completed_count INT;
    failed_count INT;
    running_count INT;
BEGIN
    SELECT COUNT(*) INTO total_workflows FROM workflow_state WHERE tenant_id = 'demo-tenant';
    SELECT COUNT(*) INTO completed_count FROM workflow_state WHERE tenant_id = 'demo-tenant' AND status = 'COMPLETED';
    SELECT COUNT(*) INTO failed_count FROM workflow_state WHERE tenant_id = 'demo-tenant' AND status = 'FAILED';
    SELECT COUNT(*) INTO running_count FROM workflow_state WHERE tenant_id = 'demo-tenant' AND status = 'RUNNING';

    RAISE NOTICE 'Demo 数据初始化完成:';
    RAISE NOTICE '  - 总 workflow 数: %', total_workflows;
    RAISE NOTICE '  - COMPLETED: %', completed_count;
    RAISE NOTICE '  - FAILED: %', failed_count;
    RAISE NOTICE '  - RUNNING: %', running_count;
END $$;
