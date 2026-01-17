-- 扩展审计日志表以支持错误信息与元数据

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS metadata TEXT;

COMMENT ON COLUMN audit_logs.error_message IS '策略评估失败时记录的错误信息';
COMMENT ON COLUMN audit_logs.metadata IS '序列化的扩展元数据';
