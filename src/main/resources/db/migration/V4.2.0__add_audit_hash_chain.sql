-- Phase 0 Task 3.1: 为 audit_logs 表增加哈希链字段
-- 实现防篡改审计链：每条记录包含前一条记录的哈希值和当前记录的哈希值

-- 增加哈希链字段（允许 NULL 以支持向后兼容）
ALTER TABLE audit_logs ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN current_hash VARCHAR(64);

-- 创建索引以优化哈希链验证查询
CREATE INDEX idx_audit_logs_current_hash ON audit_logs(current_hash);
CREATE INDEX idx_audit_logs_tenant_time ON audit_logs(tenant_id, timestamp);

-- 列注释
COMMENT ON COLUMN audit_logs.prev_hash IS '前一条审计记录的哈希值（SHA256 hex），用于构建防篡改链。NULL 表示该租户的第一条记录（genesis block）';
COMMENT ON COLUMN audit_logs.current_hash IS '当前记录的哈希值（SHA256 hex），计算规则：SHA256(prev_hash + event_type + timestamp + tenant_id + policy_module + policy_function + success)';

-- 向后兼容说明：
-- 1. 旧审计记录的 prev_hash 和 current_hash 为 NULL
-- 2. 新记录从写入时开始构建哈希链
-- 3. 每个租户独立维护哈希链（per-tenant chain）
-- 4. 哈希链验证从第一条有 current_hash 的记录开始
