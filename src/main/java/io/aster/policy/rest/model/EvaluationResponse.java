package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.api.model.DecisionTrace;

import java.util.List;

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
    DecisionTrace decisionTrace,

    @JsonProperty("executedFunction")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String executedFunction,

    @JsonProperty("diagnostics")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<EntryDiagnostic> diagnostics
) {
    public record EntryDiagnostic(
        String code,
        String message,
        List<String> candidates
    ) {}

    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, null, executedFunction, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, DecisionTrace trace) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, null, null);
    }

    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, executedFunction, null);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error, null, null, null);
    }

    public static EvaluationResponse ambiguous(List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(
            "ENTRY_AMBIGUOUS",
            "未指定入口函数，候选函数不唯一",
            safeCandidates
        );
        return new EvaluationResponse(null, 0, "入口函数不唯一", null, null, List.of(diagnostic));
    }
}
