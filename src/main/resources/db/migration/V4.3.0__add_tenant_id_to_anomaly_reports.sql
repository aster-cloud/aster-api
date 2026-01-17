-- V4.3.0: 为 anomaly_reports 添加 tenant_id 列以支持多租户隔离
--
-- 背景：
-- - Phase 3 多租户隔离测试发现 anomaly_reports 缺少租户维度，导致高风险数据泄露
-- - 任意租户可查看所有租户的异常报告和 rollback 历史
--
-- 变更：
-- 1. 添加 tenant_id 列（默认 'default'）
-- 2. 迁移现有数据：为已有记录设置 tenant_id='default'
-- 3. 添加 NOT NULL 约束
-- 4. 添加复合索引 (tenant_id, detected_at) 优化查询性能
--
-- Rollback:
-- - 见文件末尾的 rollback SQL

-- Step 1: 添加 tenant_id 列（允许 NULL，用于迁移）
ALTER TABLE anomaly_reports
ADD COLUMN tenant_id VARCHAR(255);

-- Step 2: 为现有记录设置默认 tenant_id
-- 策略：
-- - 如果 policy_id 包含 'tenant-' 前缀，提取租户名称
-- - 否则统一设置为 'default'
UPDATE anomaly_reports
SET tenant_id = CASE
    WHEN policy_id LIKE 'tenant-%' THEN SPLIT_PART(policy_id, '-', 2)
    ELSE 'default'
END
WHERE tenant_id IS NULL;

-- Step 3: 添加 NOT NULL 约束
ALTER TABLE anomaly_reports
ALTER COLUMN tenant_id SET NOT NULL;

-- Step 4: 添加复合索引优化查询
-- 用途：支持 "WHERE tenant_id = ? ORDER BY detected_at DESC" 查询
CREATE INDEX idx_anomaly_reports_tenant_detected
ON anomaly_reports(tenant_id, detected_at DESC);

-- Step 5: 添加注释
COMMENT ON COLUMN anomaly_reports.tenant_id IS '租户ID，用于多租户数据隔离';

-- =============================================
-- Rollback SQL（仅在需要回滚时手动执行）
-- =============================================
-- DROP INDEX IF EXISTS idx_anomaly_reports_tenant_detected;
-- ALTER TABLE anomaly_reports DROP COLUMN IF EXISTS tenant_id;
