package io.aster.policy.telemetry;

/**
 * NSM 事件名常量
 *
 * 与前端 aster-cloud/src/lib/mixpanel.ts Events 中的 NSM 事件保持一致。
 */
public final class NsmEvents {

    private NsmEvents() {
    }

    public static final String DRAFT_PUBLISHED = "draft_published";
    public static final String RULE_ROLLED_BACK = "rule_rolled_back";
}
