-- 审计哈希链 top-1 查询改按 id 排序（issue #115 fork 修复）
--
-- AuditLog.findLatestHash(tenant) 从
--   ... ORDER BY timestamp DESC, id DESC LIMIT 1
-- 改为
--   ... WHERE tenant_id = ? AND current_hash IS NOT NULL ORDER BY id DESC LIMIT 1
--
-- 原因：并发追加（@ObservesAsync 多线程 + advisory lock 串行化写）下，事件的 wall-clock
-- timestamp（Instant.now，truncatedTo MICROS，可乱序/同微秒/时钟回拨）与实际持久化顺序
-- （id=BIGSERIAL 严格单调）不一致。按 timestamp 取「最新」会选到非链尾节点 → prev_hash
-- 指向非末端 → 链分叉。改按 id（真实追加顺序）取链尾，配合 per-tenant advisory lock，
-- 保证链无分叉，且抵御时钟回拨/乱序。
--
-- 索引对齐：新查询只按 (tenant_id, id DESC) 排序 + partial 排除空 hash 行。
-- 新增精准覆盖索引；旧的 timestamp 排序索引（V6.16.0）不再服务本查询，删除避免冗余
-- 写放大（findByTenant/findByTimeRange 由 idx_audit_logs_tenant_time(V4.2.0) 覆盖，不受影响）。
--
-- 遵循本仓 migration 惯例用普通 CREATE INDEX（非 CONCURRENTLY）；生产大表如需在线建索引，
-- 由 DBA 用 CREATE INDEX CONCURRENTLY 在低峰窗口单独执行后再对齐此 migration 的 IF NOT EXISTS。
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_id_lookup
    ON audit_logs (tenant_id, id DESC)
    WHERE current_hash IS NOT NULL;

DROP INDEX IF EXISTS idx_audit_logs_tenant_hash_lookup;
