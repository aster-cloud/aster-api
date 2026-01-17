package io.aster.policy.api.model;

import java.util.List;

/**
 * 策略组合结果，包含各步骤输出与最终结果。
 */
public class PolicyCompositionResult {

    private List<StepResult> stepResults;
    private Object finalResult;

    public PolicyCompositionResult(List<StepResult> stepResults, Object finalResult) {
        this.stepResults = stepResults;
        this.finalResult = finalResult;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public Object getFinalResult() {
        return finalResult;
    }

    public void setFinalResult(Object finalResult) {
        this.finalResult = finalResult;
    }
}
