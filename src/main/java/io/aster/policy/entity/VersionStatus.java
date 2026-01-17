package io.aster.policy.entity;

/**
 * 策略版本状态枚举
 *
 * 用于 Truffle 安全架构的版本生命周期管理和审批工作流
 */
public enum VersionStatus {
    /**
     * 草稿状态 - 版本刚创建，尚未提交审批
     */
    DRAFT,

    /**
     * 已提交 - 版本已提交，等待审批
     */
    SUBMITTED,

    /**
     * 已批准 - 版本通过审批，可以执行
     */
    APPROVED,

    /**
     * 已拒绝 - 版本审批被拒绝
     */
    REJECTED,

    /**
     * 已弃用 - 版本被标记为弃用，不推荐使用但仍可执行
     */
    DEPRECATED,

    /**
     * 已归档 - 版本已归档，不再可执行
     */
    ARCHIVED
}
