package io.aster.security.apikey;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * API key 验证结果（5 分钟 Caffeine 缓存的 value）
 *
 * - valid=true 时其余字段必填（apiKeyId, userId, tenantId, plan）
 * - valid=false 时 reason 给出原因（not_found / revoked / expired / orphan_key）
 *
 * 注意：缓存命中后 plan 字段可能略过期（最多 5 min）；
 * plan 维度的实时性由 PlanGateService 保证，此处仅作展示/审计用。
 */
@RegisterForReflection
public record ApiKeyVerifyResult(
    boolean valid,
    String reason,
    String apiKeyId,
    String userId,
    String tenantId,
    String plan,
    String subscriptionStatus
) {
    public static ApiKeyVerifyResult invalid(String reason) {
        return new ApiKeyVerifyResult(false, reason, null, null, null, null, null);
    }

    public static ApiKeyVerifyResult valid(
        String apiKeyId,
        String userId,
        String tenantId,
        String plan,
        String subscriptionStatus
    ) {
        return new ApiKeyVerifyResult(true, null, apiKeyId, userId, tenantId, plan, subscriptionStatus);
    }
}
