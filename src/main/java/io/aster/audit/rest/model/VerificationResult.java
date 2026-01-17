package io.aster.audit.rest.model;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.time.Instant;

/**
 * Workflow Replay 验证结果（Phase 3.7）
 *
 * 用于记录 Replay 验证的详细结果，包括是否成功重放、异常是否重现、性能对比等。
 *
 * @param replaySucceeded    Replay 是否成功执行
 * @param anomalyReproduced  异常是否在 Replay 中重现
 * @param workflowId         重放的 workflow ID
 * @param replayedAt         重放执行时间
 * @param originalDurationMs 原始执行耗时（毫秒）
 * @param replayDurationMs   重放执行耗时（毫秒）
 */
public record VerificationResult(
    Boolean replaySucceeded,
    Boolean anomalyReproduced,
    String workflowId,
    Instant replayedAt,
    Long originalDurationMs,
    Long replayDurationMs
) {
    /**
     * 将验证结果序列化为 JsonObject
     *
     * @return JsonObject 格式的验证结果，包含 differenceMs 计算字段
     */
    public JsonObject toJson() {
        var builder = Json.createObjectBuilder()
            .add("replaySucceeded", replaySucceeded != null ? replaySucceeded : false)
            .add("anomalyReproduced", anomalyReproduced != null ? anomalyReproduced : false)
            .add("workflowId", workflowId != null ? workflowId : "")
            .add("replayedAt", replayedAt != null ? replayedAt.toString() : "");

        if (originalDurationMs != null) {
            builder.add("originalDurationMs", originalDurationMs);
        }
        if (replayDurationMs != null) {
            builder.add("replayDurationMs", replayDurationMs);
        }

        // 计算差异（正值表示 Replay 更慢，负值表示 Replay 更快）
        if (originalDurationMs != null && replayDurationMs != null) {
            long differenceMs = replayDurationMs - originalDurationMs;
            builder.add("differenceMs", differenceMs);
        }

        return builder.build();
    }
}
