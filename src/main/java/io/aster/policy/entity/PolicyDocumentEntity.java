package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 策略文档实体（PolicyManagementService CRUD 的 DB-backed 存储）。
 *
 * <p>替代原 {@code PolicyStorageService} 的进程内 {@link java.util.concurrent.ConcurrentHashMap}
 * 存储——内存存储重启即丢、不跨副本，是 GA blocker。本实体提供租户隔离的持久化策略文档。</p>
 *
 * <p>这是面向 API 的「策略文档」（allow/deny ACL + CNL 文本），与版本化的
 * {@code policy_versions} / {@code policy_catalog} 不同：后者是不可变部署的版本资产，
 * 前者是 dashboard CRUD 编辑的可变文档。两者并存且职责分明。</p>
 */
@RegisterForReflection
@Entity
@Table(name = "policy_documents", indexes = {
    @Index(name = "idx_policy_documents_tenant", columnList = "tenant_id")
})
public class PolicyDocumentEntity extends PanacheEntityBase {

    /**
     * 策略 ID（业务主键）。由 PolicyStorageService 生成（确定性 UUID，
     * workflow replay 下复用 DeterminismContext）。
     */
    @Id
    @Column(name = "id", nullable = false, length = 100)
    public String id;

    /**
     * 租户 ID（多租户隔离）。CRUD 永远按 (tenant_id, id) 定位，避免跨租户访问。
     */
    @Column(name = "tenant_id", nullable = false, length = 200)
    public String tenantId;

    @Column(name = "name", nullable = false, length = 500)
    public String name;

    /**
     * allow 规则集：resourceType → patterns。JSONB 列。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allow_rules", columnDefinition = "jsonb")
    public Map<String, List<String>> allow;

    /**
     * deny 规则集：resourceType → patterns。JSONB 列。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deny_rules", columnDefinition = "jsonb")
    public Map<String, List<String>> deny;

    /**
     * CNL 源文本（可空）。
     */
    @Column(name = "cnl", columnDefinition = "text")
    public String cnl;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
