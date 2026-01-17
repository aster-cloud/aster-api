package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * REST API响应：批量策略评估结果
 *
 * 包含所有评估结果和整体执行时间。
 */
public record BatchEvaluationResponse(
    @JsonProperty("results")
    List<EvaluationResponse> results,

    @JsonProperty("totalExecutionTimeMs")
    long totalExecutionTimeMs,

    @JsonProperty("successCount")
    int successCount,

    @JsonProperty("failureCount")
    int failureCount
) {
}
