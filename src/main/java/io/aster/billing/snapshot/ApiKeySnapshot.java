package io.aster.billing.snapshot;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * API key snapshot —— 本地 redis 缓存
 *
 * keyHash 作为 redis key（SHA-256(明文)）；明文不存任何地方。
 */
@RegisterForReflection
public record ApiKeySnapshot(
    boolean valid,
    String reason,           // 仅 invalid 时有意义
    String apiKeyId,
    String userId,
    String plan,
    Long revokedAtEpochMs    // null = 未撤销
) {
    public static ApiKeySnapshot invalid(String reason) {
        return new ApiKeySnapshot(false, reason, null, null, null, null);
    }

    public boolean revoked() {
        return revokedAtEpochMs != null;
    }
}
