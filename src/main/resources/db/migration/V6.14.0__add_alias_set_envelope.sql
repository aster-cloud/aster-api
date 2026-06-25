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

ALTER TABLE policy_versions
    ADD COLUMN alias_set TEXT;

ALTER TABLE policy_versions
    ADD COLUMN source_envelope_sha256 VARCHAR(64);

COMMENT ON COLUMN policy_versions.alias_set IS
    '版本编译时冻结的用户自定义关键词别名集（JSON: kind→[别名,...]），NULL=无别名 (ADR 0022 方案 D)';

COMMENT ON COLUMN policy_versions.source_envelope_sha256 IS
    '完整编译输入的 SHA-256（content+alias_set+locale+工具链身份），防别名替换篡改 (ADR 0022 §11.5 C1)';
