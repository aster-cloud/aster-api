-- issue #119：outbox 长事务拆分（claim/handler/finalize 三段）需要 lease token
-- 区分同一 outbox 行的不同 claim attempt，避免 reclaim 重投递后旧 attempt 的迟到
-- finalize 错误覆盖新 attempt 的终态（ABA 竞态）。
--
-- claim（事务A）写入本 token；finalize（事务B）以 status=RUNNING AND lease_token=? 为条件；
-- reclaim 把过期 RUNNING 拉回 PENDING 时清空 lease_token。

ALTER TABLE anomaly_actions
    ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64);

COMMENT ON COLUMN anomaly_actions.lease_token IS 'outbox claim 租约令牌（issue #119）：每次 claim 生成，finalize/reclaim 据此判定 attempt 归属，防拆分后 ABA 覆盖';
