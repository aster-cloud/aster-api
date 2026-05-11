package io.aster.billing;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 租户的当前订阅档位信息
 *
 * 由 aster-cloud /internal/tenant/{id}/plan 接口返回，aster-api 缓存使用。
 */
@RegisterForReflection
public record PlanInfo(
    String plan,
    String legacyTier,
    boolean allowsApproval,
    int maxTeamMembers,
    long evaluationsLimit,
    long apiCallsLimit
) {

    /**
     * 默认 fail-open 实例：plan=pro 视为最宽松档位，不阻塞业务
     * 注意：仅在 plan-gate 服务不可达且 failOpen=true 时使用
     */
    public static PlanInfo failOpen() {
        return new PlanInfo("pro", null, true, -1, 50_000L, 5_000L);
    }

    public boolean isFreePlan() {
        return "free".equals(plan);
    }

    /** API 调用配额：0 = 无 API 访问；-1 = 无限；其他 = 月度上限 */
    public boolean apiAccessAllowed() {
        return apiCallsLimit != 0;
    }

    public boolean unlimitedApi() {
        return apiCallsLimit == -1;
    }
}
