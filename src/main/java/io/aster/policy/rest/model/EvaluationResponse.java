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
    List<EntryDiagnostic> diagnostics,

    // 回放元数据（ADR 0030 附录 A）——仅 replay-capture 模式产出（否则 null）。cloud BFF 拿到
    // 后写 Execution 新列（runtimeToolchainId/canonical*Hash/traceHash/...）。权威 hash 由本侧
    // （Java 评估侧）计算，cloud 只存储。
    @JsonProperty("replayMetadata")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    io.aster.policy.replay.ReplayMetadata replayMetadata
) {
    public record EntryDiagnostic(
        String code,
        String message,
        List<String> candidates
    ) {}

    public static EvaluationResponse success(Object result, long executionTimeMs) {
        return new EvaluationResponse(result, executionTimeMs, null, null, null, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, null, executedFunction, null, null);
    }

    public static EvaluationResponse success(Object result, long executionTimeMs, DecisionTrace trace) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, null, null, null);
    }

    public static EvaluationResponse success(
            Object result, long executionTimeMs, DecisionTrace trace, String executedFunction) {
        return new EvaluationResponse(result, executionTimeMs, null, trace, executedFunction, null, null);
    }

    public static EvaluationResponse error(String error) {
        return new EvaluationResponse(null, 0, error, null, null, null, null);
    }

    public static EvaluationResponse ambiguous(List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(
            "ENTRY_AMBIGUOUS",
            "未指定入口函数，候选函数不唯一",
            safeCandidates
        );
        return new EvaluationResponse(null, 0, "入口函数不唯一", null, null, List.of(diagnostic), null);
    }

    public static EvaluationResponse diagnostic(String code, String message, List<String> candidates) {
        List<String> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        EntryDiagnostic diagnostic = new EntryDiagnostic(code, message, safeCandidates);
        return new EvaluationResponse(null, 0, message, null, null, List.of(diagnostic), null);
    }

    /** 附加回放元数据（replay-capture 模式；不改其它字段）。 */
    public EvaluationResponse withReplayMetadata(io.aster.policy.replay.ReplayMetadata rm) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction, diagnostics, rm);
    }
}
