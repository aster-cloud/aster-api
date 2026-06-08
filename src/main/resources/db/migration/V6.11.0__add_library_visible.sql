-- ADR 0015 阶段3：跨模块引用（library 可见性）
--
-- 背景：
--   Use risk.Scoring as v2 让一个 team 引用另一个 team 已发布的模块。
--   出于安全（tenant 隔离 + 显式发布治理），一个策略版本只有显式标记
--   library_visible = true 后，才能被其它策略经 Use 引用。
--   默认 false：历史发布的策略不会自动暴露为可引用 library。
--
-- ModuleResolver（aster-api）按 (tenant_id, module_name, version, library_visible=true)
-- 查询被引模块的 content（CNL 源码），加载时重新编译成 Core IR（带缓存）。

ALTER TABLE policy_versions
    ADD COLUMN library_visible BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN policy_versions.library_visible IS
    '是否可作为 library 被其它策略经 Use 引用（ADR 0015 阶段3）。默认 false，需显式发布为可引用。';

-- ModuleResolver 查询索引：按 tenant + 模块名 + 版本 + 可见性定位被引模块版本
CREATE INDEX idx_policy_versions_library_lookup
    ON policy_versions (tenant_id, module_name, version, library_visible)
    WHERE library_visible = TRUE;
