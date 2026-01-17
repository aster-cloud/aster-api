-- Phase 4.1: 扩展 anomaly_actions 支持通用 Outbox

ALTER TABLE anomaly_actions
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(128);

-- 为历史数据补齐租户命名空间，确保幂等策略兼容
UPDATE anomaly_actions
SET tenant_id = CONCAT('ANOMALY-', anomaly_id)
WHERE tenant_id IS NULL;

COMMENT ON COLUMN anomaly_actions.tenant_id IS '租户/命名空间标识，用于 Outbox 幂等隔离';
