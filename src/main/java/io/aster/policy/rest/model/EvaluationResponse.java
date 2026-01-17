package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API响应：策略评估结果
 *
 * 包含评估结果、执行时间和可能的错误信息。
 */
public record EvaluationResponse(
    @JsonProperty("result")
    Object result,

    @JsonProperty("executionTimeMs")
    long executionTimeMs,

    @JsonProperty("error")
    String error
) {
    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error);
    }
}
