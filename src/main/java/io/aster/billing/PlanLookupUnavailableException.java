package io.aster.billing;

/**
 * 当 plan 信息**无法确定**（cloud lookup 故障 / 超时 / 缓存未命中且配置为
 * fail-closed）时抛出——区别于 {@link PlanLimitException}（用户档位不足，需升级）。
 *
 * <p>语义至关重要：lookup 不可用是**服务可用性**问题（→ 503 Service
 * Unavailable，可重试），而不是**计费/档位**问题（→ 402 Payment Required，
 * 误导客户端去升级套餐）。两者复用同一 mapper 会让一次 cloud 抖动被前端
 * 当成"请升级"。
 */
public class PlanLookupUnavailableException extends RuntimeException {

    private final String reason;

    public PlanLookupUnavailableException(String reason) {
        super("plan_lookup_unavailable:" + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
