-- 创建 policy_artifacts 表用于持久化策略编译产物（Core JSON、Truffle/JVM 结果等）
CREATE TABLE IF NOT EXISTS policy_artifacts
(
    id                UUID PRIMARY KEY,
    policy_version_id BIGINT       NOT NULL,
    artifact_type     VARCHAR(50)  NOT NULL,
    content           BYTEA,
    content_sha256    VARCHAR(64),
    compiler_opts     JSONB,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT policy_artifacts_version_fk FOREIGN KEY (policy_version_id) REFERENCES policy_versions (id)
);

-- 复合索引：快速定位版本及产物类型
CREATE INDEX IF NOT EXISTS idx_artifacts_version
    ON policy_artifacts (policy_version_id, artifact_type);

-- 回滚
DROP INDEX IF EXISTS idx_artifacts_version;
DROP TABLE IF EXISTS policy_artifacts;
