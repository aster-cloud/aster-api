-- 审计 #98（Medium 2）：策略版本激活的双重激活竞态。
--
-- activateVersionInternal 走 deactivate-then-activate，无行锁，也无
-- (policy_id, tenant_id) WHERE active 的部分唯一索引。手动 rollback 与
-- AnomalyActionExecutor.executeAutoRollback 并发时，两个事务都会读到并停用
-- 同一当前 active 行，再各自激活不同目标 → 可能提交出两条 active 行，
-- findActiveVersion(...).firstResult() 返回任意一条 → 生产评估不确定。
--
-- 修复：给 policy_versions 加 Hibernate @Version 乐观锁列。两个竞态事务
-- 都会 UPDATE 同一当前 active 行（把它 active=false），@Version 使后提交者
-- 命中 OptimisticLockException 回滚，保证每个 (policy_id, tenant_id) 至多
-- 一条 active 行。
--
-- 现存行填 0（DEFAULT 0）。ADD COLUMN ... DEFAULT 0 在 PG 11+ 不重写整表
-- （存元数据默认值），锁窗口短。列 NOT NULL 以匹配实体 @Column(nullable=false)。
ALTER TABLE policy_versions
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN policy_versions.lock_version IS
    'Hibernate @Version 乐观锁：防激活/回滚双重激活竞态提交出两条 active 行 (审计 #98)';
