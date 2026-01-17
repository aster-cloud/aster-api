package io.aster.policy.exception;

/**
 * 策略未找到异常
 *
 * 当请求的策略不存在或已被删除时抛出
 */
public class PolicyNotFoundException extends AsterPolicyException {

    private static final long serialVersionUID = 1L;

    /**
     * 策略标识符
     */
    private final String policyId;

    public PolicyNotFoundException(String policyId) {
        super("策略未找到: " + policyId, "POLICY_NOT_FOUND");
        this.policyId = policyId;
    }

    public PolicyNotFoundException(String message, String policyId) {
        super(message, "POLICY_NOT_FOUND");
        this.policyId = policyId;
    }

    public String getPolicyId() {
        return policyId;
    }
}
