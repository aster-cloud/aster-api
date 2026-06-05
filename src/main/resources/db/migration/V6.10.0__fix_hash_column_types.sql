-- 修正 Flyway 迁移与 Hibernate 实体之间的列类型漂移。
--
-- 背景：早期迁移把若干哈希列声明为 CHAR(64)（定宽，右侧空格填充），而实体
-- 映射为 varchar(64)。Hibernate post-boot 校验线程因此打 ERROR 日志：
--   wrong column type ... found [char], but expecting [varchar(64)]
--
-- schema-management.strategy=none，所以这不会让启动失败，但：
--   1) CHAR(64) 会用空格把值补齐到 64 字符。SHA-256 hex 恰好 64 位时无碍，
--      但任何写入更短值或对这些列做精确比较的代码，都会被尾随空格坑到
--      （潜在的哈希链 / nonce 比对错配）——把它收敛成 varchar 消除该隐患。
--   2) 同时消除每次启动的 ERROR 噪声，保持运维信号干净。
--
-- 运维须知（锁/重写）：ALTER COLUMN ... TYPE 默认取 ACCESS EXCLUSIVE 锁，
-- 且 char→varchar 改变内容语义（去掉 padding），通常会重写表/索引，**不是**
-- 纯元数据变更。这几张表数据量小，但生产执行仍建议放在低流量窗口，迁移后
-- 对相关表 ANALYZE。
--
-- 数据安全：PostgreSQL 中 character(n) 为定宽空格填充；转换到 varchar 时会
-- 移除尾随空格——这正是我们想要的修复（消除哈希列的填充隐患）。64 位 SHA
-- hex 长度恰为 64，不会丢任何哈希字符。

ALTER TABLE IF EXISTS policy_versions
    ALTER COLUMN artifact_sha256 TYPE varchar(64),
    ALTER COLUMN prev_hash TYPE varchar(64);

ALTER TABLE IF EXISTS used_nonce
    ALTER COLUMN request_hash TYPE varchar(64);

-- failure_reason 实体声明为 varchar(10000)，迁移建为 TEXT。加 10000 长度约束
-- 前先把任何历史超长值截断到 10000，避免 ALTER ... TYPE varchar(10000) 因
-- 个别超长行失败（防御性：正常 failure reason 远短于此，命中概率极低）。
UPDATE workflow_events
    SET failure_reason = left(failure_reason, 10000)
    WHERE failure_reason IS NOT NULL AND char_length(failure_reason) > 10000;

ALTER TABLE IF EXISTS workflow_events
    ALTER COLUMN failure_reason TYPE varchar(10000);
