package io.aster.policy.api.model;

import java.util.List;

/**
 * 批量执行汇总结果。
 */
public class BatchEvaluationResult {

    private final List<EvaluationAttempt> successes;
    private final List<EvaluationAttempt> failures;
    private final int successCount;
    private final int failureCount;
    private final int totalCount;

    public BatchEvaluationResult(List<EvaluationAttempt> successes,
                                 List<EvaluationAttempt> failures,
                                 int successCount,
                                 int failureCount,
                                 int totalCount) {
        this.successes = successes;
        this.failures = failures;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.totalCount = totalCount;
    }

    public List<EvaluationAttempt> getSuccesses() {
        return successes;
    }

    public List<EvaluationAttempt> getFailures() {
        return failures;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getTotalCount() {
        return totalCount;
    }
}
