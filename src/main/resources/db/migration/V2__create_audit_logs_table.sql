-- 审计日志表
-- 用于合规审计和事后调查，记录所有关键策略操作

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    performed_by VARCHAR(100),
    policy_module VARCHAR(200),
    policy_function VARCHAR(200),
    policy_id VARCHAR(100),
    from_version BIGINT,
    to_version BIGINT,
    execution_time_ms BIGINT,
    success BOOLEAN,
    reason VARCHAR(500),
    notes VARCHAR(1000),
    client_ip VARCHAR(50),
    user_agent VARCHAR(500)
);

-- 索引：提升查询性能
CREATE INDEX idx_audit_tenant ON audit_logs(tenant_id);
CREATE INDEX idx_audit_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_policy ON audit_logs(policy_module, policy_function);

-- 注释
COMMENT ON TABLE audit_logs IS '审计日志表 - 记录所有策略操作用于合规审计';
COMMENT ON COLUMN audit_logs.event_type IS '事件类型: POLICY_EVALUATION, POLICY_CREATED, POLICY_ROLLBACK';
COMMENT ON COLUMN audit_logs.tenant_id IS '租户ID - 多租户隔离';
COMMENT ON COLUMN audit_logs.performed_by IS '执行者ID - 从 X-User-Id 头部提取';
COMMENT ON COLUMN audit_logs.client_ip IS '客户端IP地址 - 已脱敏';
