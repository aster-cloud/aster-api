package io.aster.policy.service;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 策略版本服务
 *
 * 提供策略版本管理功能：
 * - 创建新版本（自动停用旧版本）
 * - 查询版本历史
 * - 回滚到指定版本
 */
@ApplicationScoped
public class PolicyVersionService {

    private static final Logger LOG = Logger.getLogger(PolicyVersionService.class);

    @Inject
    EntityManager entityManager;

    /**
     * 创建策略版本。
     *
     * 步骤：
     * 1. 校验 catalog 并写入策略版本记录
     * 2. 策略源码存入数据库，运行时动态解析执行
     *
     * @param catalogId 策略目录 ID
     * @param sourceCnl 策略 CNL 源码
     * @param locale    策略语言环境
     * @return 新建的策略版本
     */
    @Transactional
    public PolicyVersion createVersion(UUID catalogId, String sourceCnl, String locale) {
        PolicyCatalog catalog = PolicyCatalog.findById(catalogId);
        if (catalog == null) {
            throw new IllegalArgumentException("策略目录不存在: catalogId=" + catalogId);
        }

        String policyId = catalog.moduleName + "." + catalog.functionName;
        PolicyVersion version = new PolicyVersion(
            policyId,
            catalog.moduleName,
            catalog.functionName,
            sourceCnl,
            "system",
            "Dynamic policy version"
        );
        version.tenantId = catalog.tenantId;
        version.locale = locale;
        version.active = false; // 新版本默认保持非活跃，待 activateVersion 显式启用
        version.persist();

        return version;
    }

    /**
     * 提交指定版本至审批流程。
     *
     * @param versionId  版本主键
     * @param submittedBy 提交人
     * @return 更新后的策略版本
     */
    @Transactional
    public PolicyVersion submitForApproval(Long versionId, String submittedBy) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.DRAFT) {
            throw new IllegalStateException(
                String.format("仅草稿版本可提交审批: versionId=%d, status=%s", versionId, version.status)
            );
        }

        version.status = VersionStatus.SUBMITTED;
        version.submittedBy = submittedBy;
        version.submittedAt = Instant.now();
        version.rejectedBy = null;
        version.rejectedAt = null;
        version.persist();
        return version;
    }

    /**
     * 审批通过指定版本。
     *
     * @param versionId 版本主键
     * @param approvedBy 审批人
     * @return 已批准的策略版本
     */
    @Transactional
    public PolicyVersion approveVersion(Long versionId, String approvedBy) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.SUBMITTED) {
            throw new IllegalStateException(
                String.format("仅已提交版本可审批通过: versionId=%d, status=%s", versionId, version.status)
            );
        }

        version.status = VersionStatus.APPROVED;
        version.approvedBy = approvedBy;
        version.approvedAt = Instant.now();
        version.rejectedBy = null;
        version.rejectedAt = null;
        version.persist();
        return version;
    }

    /**
     * 拒绝指定版本的审批请求。
     *
     * @param versionId 版本主键
     * @param rejectedBy 拒绝人
     * @param reason     拒绝原因
     * @return 已拒绝的策略版本
     */
    @Transactional
    public PolicyVersion rejectVersion(Long versionId, String rejectedBy, String reason) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.SUBMITTED) {
            throw new IllegalStateException(
                String.format("仅已提交版本可被拒绝: versionId=%d, status=%s", versionId, version.status)
            );
        }

        version.status = VersionStatus.REJECTED;
        version.rejectedBy = rejectedBy;
        version.rejectedAt = Instant.now();
        version.approvedBy = null;
        version.approvedAt = null;
        if (reason != null && !reason.isBlank()) {
            String rejectionNote = "[Rejected] " + reason.trim();
            if (version.notes == null || version.notes.isBlank()) {
                version.notes = rejectionNote;
            } else {
                version.notes = version.notes + System.lineSeparator() + rejectionNote;
            }
        }
        version.persist();
        return version;
    }

    /**
     * 激活指定版本并更新 catalog 默认版本，同时通知依赖组件刷新缓存。
     *
     * @param versionId   策略版本主键
     * @param activatedBy 激活操作人
     */
    @Transactional
    public void activateVersion(Long versionId, String activatedBy) {
        PolicyVersion version = requireVersion(versionId);
        if (version.status != VersionStatus.APPROVED) {
            throw new IllegalStateException(
                String.format("仅已审批通过的版本可激活: versionId=%d, status=%s", versionId, version.status)
            );
        }

        PolicyVersion.deactivateAllVersions(version.policyId);
        version.active = true;
        version.activatedAt = Instant.now();
        version.activatedBy = activatedBy;
        version.persist();

        PolicyCatalog catalog = PolicyCatalog.find(
            "tenantId = ?1 and moduleName = ?2 and functionName = ?3",
            version.tenantId,
            version.moduleName,
            version.functionName
        ).firstResult();
        if (catalog != null) {
            catalog.defaultVersionId = versionId;
            catalog.updatedAt = Instant.now();
            catalog.persist();
        }

        emitActivationNotification(version, catalog);
    }

    /**
     * 创建新版本
     *
     * 自动停用旧的活跃版本，确保每个 policyId 只有一个活跃版本。
     *
     * @param policyId     策略ID
     * @param moduleName   模块名
     * @param functionName 函数名
     * @param content      策略内容
     * @param createdBy    创建者
     * @param notes        备注
     * @return 新创建的版本
     */
    @Transactional
    public PolicyVersion createVersion(
        String policyId,
        String moduleName,
        String functionName,
        String content,
        String createdBy,
        String notes
    ) {
        // 停用旧的活跃版本
        PolicyVersion.deactivateAllVersions(policyId);

        // 创建新版本
        PolicyVersion newVersion = new PolicyVersion(
            policyId,
            moduleName,
            functionName,
            content,
            createdBy,
            notes
        );

        newVersion.persist();

        return newVersion;
    }

    /**
     * 获取活跃版本
     *
     * @param policyId 策略ID
     * @return 活跃版本，如果不存在返回 null
     */
    public PolicyVersion getActiveVersion(String policyId) {
        return PolicyVersion.findActiveVersion(policyId);
    }

    /**
     * 获取所有版本（按版本号降序）
     *
     * @param policyId 策略ID
     * @return 版本列表
     */
    public List<PolicyVersion> getAllVersions(String policyId) {
        return PolicyVersion.findAllVersions(policyId);
    }

    /**
     * 获取指定版本
     *
     * @param policyId 策略ID
     * @param version  版本号
     * @return 版本实体，如果不存在返回 null
     */
    public PolicyVersion getVersion(String policyId, Long version) {
        return PolicyVersion.findByVersion(policyId, version);
    }

    /**
     * 回滚到指定版本
     *
     * 停用当前活跃版本，激活指定版本。
     *
     * @param policyId 策略ID
     * @param version  目标版本号
     * @return 回滚后的活跃版本
     * @throws IllegalArgumentException 如果目标版本不存在
     */
    @Transactional
    public PolicyVersion rollbackToVersion(String policyId, Long version) {
        // 查找目标版本
        PolicyVersion targetVersion = PolicyVersion.findByVersion(policyId, version);

        if (targetVersion == null) {
            throw new IllegalArgumentException(
                String.format("版本不存在: policyId=%s, version=%d", policyId, version)
            );
        }

        // 停用所有活跃版本
        PolicyVersion.deactivateAllVersions(policyId);

        // 激活目标版本
        targetVersion.active = true;
        targetVersion.persist();

        return targetVersion;
    }

    /**
     * 删除策略的所有版本
     *
     * 注意：此操作不可逆，仅用于测试或管理目的。
     *
     * @param policyId 策略ID
     * @return 删除的版本数量
     */
    @Transactional
    public long deleteAllVersions(String policyId) {
        return PolicyVersion.delete("policyId = ?1", policyId);
    }

    private PolicyVersion requireVersion(Long versionId) {
        PolicyVersion version = PolicyVersion.findById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("策略版本不存在: versionId=" + versionId);
        }
        return version;
    }

    private void emitActivationNotification(PolicyVersion version, PolicyCatalog catalog) {
        if (entityManager == null) {
            return;
        }

        String tenantId = catalog != null && catalog.tenantId != null ? catalog.tenantId : version.tenantId;
        String moduleName = catalog != null ? catalog.moduleName : version.moduleName;
        String functionName = catalog != null ? catalog.functionName : version.functionName;
        String payload = String.format(
            "{\"tenantId\":\"%s\",\"module\":\"%s\",\"function\":\"%s\",\"versionId\":%d}",
            tenantId,
            moduleName,
            functionName,
            version.id
        );

        try {
            entityManager
                .createNativeQuery("SELECT pg_notify('policy_version_activated', :payload)")
                .setParameter("payload", payload)
                .getSingleResult();
        } catch (Exception e) {
            LOG.warnf(e, "发送策略版本激活通知失败: versionId=%d", version.id);
        }
    }
}
