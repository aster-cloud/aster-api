-- H2 版本：擴展審計日誌欄位

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS metadata CLOB;
