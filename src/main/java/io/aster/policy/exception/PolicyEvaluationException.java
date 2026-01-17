package io.aster.policy.exception;

/**
 * 策略评估异常
 *
 * 当策略执行过程中发生错误时抛出
 */
public class PolicyEvaluationException extends AsterPolicyException {

    private static final long serialVersionUID = 1L;

    /**
     * 策略名称
     */
    private final String policyName;

    /**
     * 策略版本
     */
    private final String policyVersion;

    public PolicyEvaluationException(String message) {
        this(message, null, null);
    }

    public PolicyEvaluationException(String message, String policyName) {
        this(message, policyName, null);
    }

    public PolicyEvaluationException(String message, String policyName, String policyVersion) {
        super(message, "POLICY_EVALUATION_ERROR");
        this.policyName = policyName;
        this.policyVersion = policyVersion;
    }

    public PolicyEvaluationException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public PolicyEvaluationException(String message, String policyName, String policyVersion, Throwable cause) {
        super(message, "POLICY_EVALUATION_ERROR", cause);
        this.policyName = policyName;
        this.policyVersion = policyVersion;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }
}
