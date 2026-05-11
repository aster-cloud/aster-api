package io.aster.billing.snapshot;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 用户配额 snapshot —— 本地 redis 缓存的 value 类型
 *
 * 由 cloud 主动推送（SnapshotPushResource）或 lazy fetch（cloud /precheck）填充。
 * TTL 1h；对账 cron 每小时全量校准。
 */
@RegisterForReflection
public record UserSnapshot(
    String userId,
    String plan,
    long apiCallsLimit,
    String subscriptionStatus,
    Long aiBannedUntilEpochMs,   // null = 未封禁
    Long gracePeriodEndsEpochMs  // null = 无 grace period
) {
    public boolean apiAccessAllowed() {
        return apiCallsLimit != 0;
    }

    public boolean unlimitedApi() {
        return apiCallsLimit == -1;
    }

    public boolean banned(long now) {
        return aiBannedUntilEpochMs != null && aiBannedUntilEpochMs > now;
    }
}
