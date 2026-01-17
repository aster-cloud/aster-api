package io.aster.policy.entity;

/**
 * 策略编译产物类型定义，确保仓库查询与数据库中的 artifact_type 字段一致。
 */
public enum ArtifactType {
    CORE_JSON,
    AST_JSON,
    BYTECODE,
    ARCHIVE
}
