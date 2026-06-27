-- ADR 0022 方案 D：用户自定义关键词别名的版本固化。
--
-- alias_set：该版本编译时使用的用户自定义别名集（JSON 文本，kind→[别名,...]）。
--   不可变——版本一经创建即冻结，rollback 激活目标版本行=自动使用其冻结的 alias_set
--   （rollbackToVersion 走 activateVersionInternal(targetVersion.id) 激活已存行，不重编译、
--    不读当前配置，故无需额外 rollback-copy 逻辑）。NULL 表示无用户别名（绝大多数版本）。
--
-- source_envelope_sha256：覆盖完整编译输入的哈希（Codex 复核 Critical-1）。
--   旧的 source_hash 只哈希 content 文本，存在"源码哈希对得上、别名被替换"的篡改窗口。
--   envelope 用规范 JSON 字节覆盖 content + alias_set + locale + 工具链身份
--   （compiler/canonicalizer/lexicon/validator 版本），作为审计/可复现的完整真相。
--   NULL 表示该版本创建早于本特性（向后兼容；新版本应填充）。

-- source_toolchain_id：envelope 计算所用的工具链身份串（abi/core/validator/build）。
--   存它使 tip-anchor verifier 能用**创建时**的工具链重算 envelope 验证最新行（无后继版本
--   断链），把篡改与引擎升级区分开（ADR 0022 §11.5 H6 + envelope verifier）。NULL=旧版本。

-- 合并为单条 ALTER：减少 ACCESS EXCLUSIVE 锁窗口（多条各拿一次锁）。
-- PG nullable ADD COLUMN 无 DEFAULT 不重写整表，但仍短暂持锁。
ALTER TABLE policy_versions
    ADD COLUMN alias_set TEXT,
    ADD COLUMN source_envelope_sha256 VARCHAR(64),
    ADD COLUMN source_toolchain_id VARCHAR(256);

COMMENT ON COLUMN policy_versions.alias_set IS
    '版本编译时冻结的用户自定义关键词别名集（JSON: kind→[别名,...]），NULL=无别名 (ADR 0022 方案 D)';

COMMENT ON COLUMN policy_versions.source_envelope_sha256 IS
    '完整编译输入的 SHA-256（content+alias_set+locale+工具链身份），防别名替换篡改 (ADR 0022 §11.5 C1)';

COMMENT ON COLUMN policy_versions.source_toolchain_id IS
    'envelope 计算所用工具链身份（abi/core/validator/build），供 tip-anchor verifier 重算验证 (ADR 0022 §11.5)';
