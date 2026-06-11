-- 策略文档 DB-backed 存储（清除 GA blocker：内存存储重启即丢）
--
-- 背景：
--   PolicyManagementService 的 CRUD（dashboard 编辑的策略文档：name + allow/deny
--   ACL + CNL 文本）原先 backing 在 PolicyStorageService 的进程内
--   ConcurrentHashMap——重启即丢、不跨副本共享。窄 SKU /evaluate GA 不能建在
--   会丢数据的存储上，故改为 DB 持久化。
--
--   与 policy_versions / policy_catalog 不同：后者是不可变部署的版本化资产，
--   前者是可变的 dashboard CRUD 文档。两者职责分明、并存。
--
-- 隔离：永远按 (tenant_id, id) 访问，确保多租户隔离。

CREATE TABLE policy_documents (
    id          VARCHAR(100) NOT NULL,
    tenant_id   VARCHAR(200) NOT NULL,
    name        VARCHAR(500) NOT NULL,
    allow_rules JSONB,
    deny_rules  JSONB,
    cnl         TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_policy_documents PRIMARY KEY (id)
);

COMMENT ON TABLE policy_documents IS
    'PolicyManagementService CRUD 的 DB-backed 策略文档存储，替代原内存 ConcurrentHashMap（GA blocker）。';

-- 按租户列表查询（listPolicies）的索引
CREATE INDEX idx_policy_documents_tenant
    ON policy_documents (tenant_id);
