package io.aster.policy.api.model;

/**
 * 策略组合步骤描述。
 */
public class CompositionStep {

    public String policyModule;
    public String policyFunction;
    public boolean useResultAsInput;

    public CompositionStep() {
    }

    public CompositionStep(String policyModule, String policyFunction, boolean useResultAsInput) {
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.useResultAsInput = useResultAsInput;
    }
}
