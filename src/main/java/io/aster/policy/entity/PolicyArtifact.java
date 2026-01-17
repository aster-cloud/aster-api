package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * PolicyArtifact 实体
 *
 * 该实体映射 policy_artifacts 表，用于存储针对策略版本生成的编译产物及其元数据。
 * 日期：2026-01-15 14:58 NZST
 */
@RegisterForReflection
@Entity
@Table(name = "policy_artifacts")
public class PolicyArtifact extends PanacheEntityBase {

    /**
     * 主键，使用 UUID 表示唯一的策略产物记录
     */
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * 策略版本ID，关联 policy_versions 表中的记录
     */
    @Column(name = "policy_version_id", nullable = false)
    public Long policyVersionId;

    /**
     * 产物类型，例如 AST、IR、字节码或压缩包
     */
    @Column(name = "artifact_type", nullable = false, length = 50)
    public String artifactType;

    /**
     * 产物内容，使用 BYTEA 存储二进制数据
     */
    @Column(name = "content", nullable = false)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    public byte[] content;

    /**
     * 产物内容的 SHA-256 校验和
     */
    @Column(name = "content_sha256", nullable = false, length = 64)
    public String contentSha256;

    /**
     * 编译选项，使用 JSONB 存储可扩展配置
     */
    @Column(name = "compiler_opts", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    public String compilerOpts;

    /**
     * 记录产物创建时间，便于审计
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
