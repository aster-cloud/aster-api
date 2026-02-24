package io.aster.policy.security.rbac;

/**
 * 系统角色定义
 * 与 aster-cloud 的 TeamRole enum 对齐（owner/admin/member/viewer）
 */
public enum Role {
    /** 拥有者：所有权限 */
    OWNER,
    /** 管理员：管理策略、查看审计 */
    ADMIN,
    /** 成员：执行策略、查看结果 */
    MEMBER,
    /** 观察者：只读访问 */
    VIEWER;

    /**
     * 检查当前角色是否拥有目标角色的权限
     * 权限等级：OWNER > ADMIN > MEMBER > VIEWER
     */
    public boolean hasAtLeast(Role required) {
        return this.ordinal() <= required.ordinal();
    }
}
