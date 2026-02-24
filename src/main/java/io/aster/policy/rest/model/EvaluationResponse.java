package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.api.model.DecisionTrace;

/**
 * REST API响应：策略评估结果
 *
 * 包含评估结果、执行时间、决策追踪和可能的错误信息。
 */
public record EvaluationResponse(
    @JsonProperty("result")
    Object result,

    @JsonProperty("executionTimeMs")
    long executionTimeMs,

    @JsonProperty("error")
    String error,

    @JsonProperty("decisionTrace")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    DecisionTrace decisionTrace
) {
    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, DecisionTrace trace) {
        return new EvaluationResponse(result, executionTimeMs, null, trace);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error, null);
    }
}
