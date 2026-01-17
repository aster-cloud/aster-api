package io.aster.policy.api.model;

/**
 * 组合执行中的单步结果。
 */
public class StepResult {

    private final String policyModule;
    private final String policyFunction;
    private final Object result;
    private final double executionTimeMs;
    private final int stepIndex;

    public StepResult(String policyModule,
                      String policyFunction,
                      Object result,
                      double executionTimeMs,
                      int stepIndex) {
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.result = result;
        this.executionTimeMs = executionTimeMs;
        this.stepIndex = stepIndex;
    }

    public String getPolicyModule() {
        return policyModule;
    }

    public String getPolicyFunction() {
        return policyFunction;
    }

    public Object getResult() {
        return result;
    }

    public double getExecutionTimeMs() {
        return executionTimeMs;
    }

    public int getStepIndex() {
        return stepIndex;
    }
}
