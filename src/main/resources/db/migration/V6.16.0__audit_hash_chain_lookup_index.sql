-- 审计哈希链 top-1 查询优化（findLatestHash 热点）
--
-- AuditLog.findLatestHash(tenant) 每次审计写入都执行：
--   WHERE tenant_id = ? AND current_hash IS NOT NULL ORDER BY timestamp DESC, id DESC LIMIT 1
-- 取该租户链上最新一条 hash 作为下一条的 prev_hash（per-tenant 哈希链，防篡改核心）。
--
-- 现有 idx_audit_logs_tenant_time (tenant_id, timestamp)（V4.2.0）能定位租户 + 反向扫
-- timestamp，但有三处退化：
--   1. 升序索引反向扫（backward scan）；
--   2. 排序次键 id 不在索引 —— 同一 timestamp（truncatedTo(MICROS)，高并发同租户同微秒可能）
--      需回表比较 id；
--   3. current_hash IS NOT NULL 谓词不在索引 —— 有大量历史空 hash 行的租户要跳过尾部空行。
-- 随审计表增长，这条 top-1 查询逐渐变慢（在 @ObservesAsync 后台线程，影响审计写入吞吐/积压）。
--
-- 本 partial 降序覆盖索引精准匹配该查询：租户等值定位 → 已按 (timestamp DESC, id DESC) 有序
-- → partial 条件天然排除空 hash 行 → 直接取 top-1，稳定 O(log n) 不随表增长退化。
-- 对防篡改语义零影响（纯读路径加速，不改写入/hash 计算/链语义）。
--
-- 保留 idx_audit_logs_tenant_time（服务 findByTenant/findByTimeRange 分页），不删。
-- 遵循本仓 migration 惯例用普通 CREATE INDEX（非 CONCURRENTLY）；生产大表如需在线建索引，
-- 由 DBA 用 CREATE INDEX CONCURRENTLY 在低峰窗口单独执行后再对齐此 migration 的 IF NOT EXISTS。
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_hash_lookup
    ON audit_logs (tenant_id, timestamp DESC, id DESC)
    WHERE current_hash IS NOT NULL;
