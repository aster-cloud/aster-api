package io.aster.policy.api.model;

import java.util.Objects;

/**
 * 策略评估结果，包含最终输出、耗时和缓存命中标记。
 */
public class PolicyEvaluationResult {

    private final Object result;
    private final double executionTimeMs;
    private final boolean fromCache;

    public PolicyEvaluationResult(Object result, double executionTimeMs, boolean fromCache) {
        this.result = result;
        this.executionTimeMs = executionTimeMs;
        this.fromCache = fromCache;
    }

    public Object getResult() {
        return result;
    }

    public double getExecutionTimeMs() {
        return executionTimeMs;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PolicyEvaluationResult that = (PolicyEvaluationResult) o;
        return Double.compare(that.executionTimeMs, executionTimeMs) == 0
            && fromCache == that.fromCache
            && Objects.equals(result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, executionTimeMs, fromCache);
    }
}
