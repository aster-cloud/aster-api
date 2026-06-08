package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 策略版本实体
 *
 * 实现不可变部署：每次策略更新都创建新版本，旧版本标记为非活跃。
 * 使用 timestamp 作为版本号确保唯一性和排序。
 */
@RegisterForReflection
@Entity
@Table(name = "policy_versions", indexes = {
    @Index(name = "idx_policy_id_active", columnList = "policy_id,active"),
    @Index(name = "idx_policy_id_version", columnList = "policy_id,version")
})
public class PolicyVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 策略唯一标识符（业务ID，跨版本不变）
     */
    @Column(name = "policy_id", nullable = false, length = 100)
    public String policyId;

    /**
     * 版本号（使用 timestamp 确保唯一性和时间顺序）
     */
    @Column(name = "version", nullable = false)
    public Long version;

    /**
     * 策略模块名称（如 aster.finance.loan）
     */
    @Column(name = "module_name", nullable = false, length = 200)
    public String moduleName;

    /**
     * 策略函数名称（如 evaluateLoanEligibility）
     */
    @Column(name = "function_name", nullable = false, length = 200)
    public String functionName;

    /**
     * 策略内容（Aster CNL 代码或 JSON 配置）
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    public String content;

    /**
     * 是否为活跃版本（每个 policyId 只有一个活跃版本）
     */
    @Column(name = "active", nullable = false)
    public Boolean active;

    /**
     * 是否可作为 library 被其它策略经 {@code Use} 引用（ADR 0015 阶段3）。
     * 默认 false，需显式发布为可引用。ModuleResolver 仅解析 library_visible=true 的版本。
     */
    @Column(name = "library_visible", nullable = false)
    public Boolean libraryVisible = false;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * 创建者
     */
    @Column(name = "created_by", length = 100)
    public String createdBy;

    /**
     * 备注信息（版本变更说明）
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    public String notes;

    /**
     * 编译产物 SHA256 校验和（Phase 3.1）
     */
    @Column(name = "artifact_sha256", length = 64)
    public String artifactSha256;

    /**
     * 编译产物存储路径（Phase 3.1）
     */
    @Column(name = "artifact_uri", columnDefinition = "TEXT")
    public String artifactUri;

    /**
     * Runtime 构建版本（Phase 3.1）
     */
    @Column(name = "runtime_build", length = 50)
    public String runtimeBuild;

    /**
     * 版本激活时间（Phase 3.1）
     * 用于审计时间线分析
     */
    @Column(name = "activated_at")
    public Instant activatedAt;

    /**
     * 提交审批的操作者
     */
    @Column(name = "submitted_by", length = 100)
    public String submittedBy;

    /**
     * 提交审批的时间
     */
    @Column(name = "submitted_at")
    public Instant submittedAt;

    /**
     * 审批通过的操作者
     */
    @Column(name = "approved_by", length = 100)
    public String approvedBy;

    /**
     * 审批通过时间
     */
    @Column(name = "approved_at")
    public Instant approvedAt;

    /**
     * 激活操作人
     */
    @Column(name = "activated_by", length = 100)
    public String activatedBy;

    /**
     * 审批拒绝的操作者
     */
    @Column(name = "rejected_by", length = 100)
    public String rejectedBy;

    /**
     * 审批拒绝的时间
     */
    @Column(name = "rejected_at")
    public Instant rejectedAt;

    /**
     * 策略源码 SHA256 哈希（十六进制）
     * 用于内容完整性校验和链式信任
     */
    @Column(name = "source_hash", length = 64, nullable = false)
    public String sourceHash;

    /**
     * 前序版本的源码哈希
     * 用于构建链式信任，形成不可篡改的版本链
     */
    @Column(name = "prev_hash", length = 64)
    public String prevHash;

    /**
     * 版本状态
     * DRAFT/SUBMITTED/APPROVED/REJECTED/DEPRECATED/ARCHIVED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    public VersionStatus status = VersionStatus.DRAFT;

    /**
     * 标记当前租户默认版本
     * 每个租户的每个策略只能有一个默认版本
     */
    @Column(name = "is_default", nullable = false)
    public Boolean isDefault = false;

    /**
     * 变更说明 / Release Note
     * 记录本版本的主要变更内容
     */
    @Column(name = "release_note", columnDefinition = "TEXT")
    public String releaseNote;

    /**
     * 版本弃用时间戳
     */
    @Column(name = "deprecated_at")
    public Instant deprecatedAt;

    /**
     * 版本弃用操作人
     */
    @Column(name = "deprecated_by", length = 100)
    public String deprecatedBy;

    /**
     * 版本归档时间戳
     */
    @Column(name = "archived_at")
    public Instant archivedAt;

    /**
     * 版本归档操作人
     */
    @Column(name = "archived_by", length = 100)
    public String archivedBy;

    /**
     * 租户标识
     * 在多租户部署中隔离策略版本
     */
    @Column(name = "tenant_id", length = 100)
    public String tenantId;

    /**
     * 核心 IR JSON 内容
     * 用于动态加载策略执行结构
     */
    @Column(name = "core_json", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    public String coreJson;

    /**
     * 编译器版本
     * 记录生成该版本策略所使用的编译器
     */
    @Column(name = "compiler_version", length = 50)
    public String compilerVersion;

    /**
     * 版本来源类型
     * 用于北极星指标 WAADR：每周被业务专家采纳的 AI 草稿规则数
     * 取值：manual / ai_draft / ai_draft_edited / imported
     */
    @Column(name = "source_kind", length = 32, nullable = false)
    public String sourceKind = "manual";

    /**
     * 作者业务角色（v1.2）
     * 取值：business_expert / compliance_officer / risk_analyst / engineer / admin / unknown
     * WAADR 视图仅统计业务三角色（前三者），与 PM 02-NSM 精确定义对齐
     */
    @Column(name = "author_role", length = 64, nullable = false)
    public String authorRole = "unknown";

    /**
     * 策略语言环境
     * 区分不同区域或语言的策略版本
     */
    @Column(name = "locale", length = 20)
    public String locale;

    // 无参构造函数（JPA 要求）
    public PolicyVersion() {
    }

    /**
     * JPA 持久化前回调
     * 确保必填字段有默认值
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.version == null) {
            this.version = Instant.now().toEpochMilli();
        }
        if (this.status == null) {
            this.status = VersionStatus.DRAFT;
        }
        if (this.isDefault == null) {
            this.isDefault = false;
        }
        if (this.active == null) {
            this.active = true;
        }
        if (this.sourceKind == null) {
            this.sourceKind = "manual";
        }
        if (this.authorRole == null) {
            this.authorRole = "unknown";
        }
        // 确保 sourceHash 有值（非空约束）
        if (this.sourceHash == null && this.content != null) {
            this.sourceHash = computeSourceHash(this.content);
        } else if (this.sourceHash == null) {
            // 如果没有内容，使用空字符串的哈希
            this.sourceHash = computeSourceHash("");
        }
    }

    /**
     * 创建新版本
     *
     * @param policyId     策略ID
     * @param moduleName   模块名
     * @param functionName 函数名
     * @param content      策略内容
     * @param createdBy    创建者
     * @param notes        备注
     */
    public PolicyVersion(
        String policyId,
        String moduleName,
        String functionName,
        String content,
        String createdBy,
        String notes
    ) {
        this.policyId = policyId;
        this.version = Instant.now().toEpochMilli(); // 使用当前时间戳作为版本号
        this.moduleName = moduleName;
        this.functionName = functionName;
        this.content = content;
        this.active = true;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
        this.notes = notes;

        // 初始化安全字段
        this.sourceHash = computeSourceHash(content);
        this.prevHash = findPreviousVersionHash(policyId);
        this.status = VersionStatus.DRAFT;
        this.isDefault = false;
    }

    /**
     * 计算策略内容的 SHA-256 哈希
     */
    private static String computeSourceHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute source hash", e);
        }
    }

    /**
     * 查找前序版本的源码哈希
     */
    private static String findPreviousVersionHash(String policyId) {
        PolicyVersion prev = find("policyId = ?1 ORDER BY version DESC", policyId).firstResult();
        return prev != null ? prev.sourceHash : null;
    }

    /**
     * 停用当前版本
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 查找指定策略的活跃版本
     *
     * @param policyId 策略ID
     * @return 活跃版本，如果不存在返回 null
     */
    public static PolicyVersion findActiveVersion(String policyId) {
        return find("policyId = ?1 and active = true", policyId).firstResult();
    }

    /**
     * 查找指定策略的所有版本（按版本号降序）
     *
     * @param policyId 策略ID
     * @return 版本列表
     */
    public static java.util.List<PolicyVersion> findAllVersions(String policyId) {
        return find("policyId = ?1 order by version desc", policyId).list();
    }

    /**
     * 查找指定策略的特定版本
     *
     * @param policyId 策略ID
     * @param version  版本号
     * @return 版本实体，如果不存在返回 null
     */
    public static PolicyVersion findByVersion(String policyId, Long version) {
        return find("policyId = ?1 and version = ?2", policyId, version).firstResult();
    }

    /**
     * 查找可作为 library 被引用的模块版本（ADR 0015 阶段3 ModuleResolver）。
     *
     * <p>按 (tenant_id, module_name, version) 定位，且必须 library_visible=true。
     * tenant 隔离是安全铁律——不同 tenant 的同名模块互不可见。
     *
     * @param tenantId   引用方租户（与被引模块同租户才可见）
     * @param moduleName 被引模块名（如 risk.Scoring）
     * @param version    钉定版本号（Use ... as v2 → 2）
     * @return 匹配的 library 版本，不存在/不可见返回 null
     */
    public static PolicyVersion findLibraryVersion(String tenantId, String moduleName, Long version) {
        return find(
            "tenantId = ?1 and moduleName = ?2 and version = ?3 and libraryVisible = true",
            tenantId, moduleName, version
        ).firstResult();
    }

    /**
     * 列出某模块在 tenant 内全部 library-visible 版本号（用于「版本不存在」错误的候选提示）。
     */
    public static java.util.List<Long> findLibraryVersions(String tenantId, String moduleName) {
        return find(
            "tenantId = ?1 and moduleName = ?2 and libraryVisible = true order by version desc",
            tenantId, moduleName
        ).<PolicyVersion>list().stream().map(v -> v.version).toList();
    }

    /**
     * 停用指定策略的所有活跃版本
     *
     * @param policyId 策略ID
     * @return 停用的版本数量
     */
    public static long deactivateAllVersions(String policyId) {
        // 使用 stream() 逐个停用，确保实体状态正确
        List<PolicyVersion> activeVersions = find("policyId = ?1 and active = true", policyId).list();
        activeVersions.forEach(v -> v.active = false);
        return activeVersions.size();
    }
}
