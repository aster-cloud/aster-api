package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 策略审批记录实体
 *
 * 实现四眼原则（Four-Eyes Principle）的审批工作流
 * 每个策略版本需要经过多个审批步骤才能激活
 */
@RegisterForReflection
@Entity
@Table(name = "policy_approval", indexes = {
    @Index(name = "idx_policy_approval_version", columnList = "policy_version_id"),
    @Index(name = "idx_policy_approval_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_policy_approval_version_step", columnNames = {"policy_version_id", "step"})
})
public class PolicyApproval extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 关联的策略版本ID
     */
    @Column(name = "policy_version_id", nullable = false)
    public Long policyVersionId;

    /**
     * 审批步骤
     * 1 = 提交人签名
     * 2 = 审批人审批
     */
    @Column(name = "step", nullable = false)
    public Short step;

    /**
     * 审批人ID
     */
    @Column(name = "approver_id", nullable = false, length = 100)
    public String approverId;

    /**
     * 审批状态
     * REQUESTED - 等待审批
     * APPROVED - 已批准
     * REJECTED - 已拒绝
     */
    @Column(name = "status", nullable = false, length = 32)
    public String status = "REQUESTED";

    /**
     * 审批意见
     */
    @Column(name = "comment", columnDefinition = "TEXT")
    public String comment;

    /**
     * 决策时间
     */
    @Column(name = "decided_at")
    public Instant decidedAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    // 无参构造函数（JPA 要求）
    public PolicyApproval() {
    }

    /**
     * 创建审批记录
     *
     * @param policyVersionId 策略版本ID
     * @param step            审批步骤
     * @param approverId      审批人ID
     */
    public PolicyApproval(Long policyVersionId, Short step, String approverId) {
        this.policyVersionId = policyVersionId;
        this.step = step;
        this.approverId = approverId;
        this.status = "REQUESTED";
        this.createdAt = Instant.now();
    }

    /**
     * 批准审批
     *
     * @param comment 审批意见
     */
    public void approve(String comment) {
        this.status = "APPROVED";
        this.comment = comment;
        this.decidedAt = Instant.now();
    }

    /**
     * 拒绝审批
     *
     * @param comment 拒绝原因
     */
    public void reject(String comment) {
        this.status = "REJECTED";
        this.comment = comment;
        this.decidedAt = Instant.now();
    }

    /**
     * 查找指定版本的所有审批记录
     *
     * @param policyVersionId 策略版本ID
     * @return 审批记录列表
     */
    public static java.util.List<PolicyApproval> findByVersionId(Long policyVersionId) {
        return find("policyVersionId = ?1 order by step", policyVersionId).list();
    }

    /**
     * 查找指定版本的特定步骤审批记录
     *
     * @param policyVersionId 策略版本ID
     * @param step            审批步骤
     * @return 审批记录，如果不存在返回 null
     */
    public static PolicyApproval findByVersionAndStep(Long policyVersionId, Short step) {
        return find("policyVersionId = ?1 and step = ?2", policyVersionId, step).firstResult();
    }

    /**
     * 检查指定版本是否已完成所有审批
     *
     * @param policyVersionId 策略版本ID
     * @param requiredSteps   需要的审批步骤数
     * @return 是否已完成所有审批
     */
    public static boolean isFullyApproved(Long policyVersionId, int requiredSteps) {
        long approvedCount = count("policyVersionId = ?1 and status = 'APPROVED'", policyVersionId);
        return approvedCount >= requiredSteps;
    }
}
