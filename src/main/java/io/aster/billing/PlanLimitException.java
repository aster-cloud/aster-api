package io.aster.billing;

/**
 * 用户档位不足以执行请求操作时抛出
 *
 * message 格式 "upgrade_required:{reason}"，由 ExceptionMapper 转换为 402 + JSON。
 * reason 取值与前端 UpgradeBlocker 的 i18n 键对齐：
 *   reviewer_required / team_member_invite / published_rules / evaluations / sso / data_residency / audit_retention
 */
public class PlanLimitException extends RuntimeException {

    private final String reason;

    public PlanLimitException(String reason) {
        super("upgrade_required:" + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
