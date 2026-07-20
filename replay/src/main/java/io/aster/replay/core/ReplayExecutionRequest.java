package io.aster.replay.core;

import java.util.List;
import java.util.Map;

/**
 * {@link ReplayExecutionCore#execute} 的入参：一次 replay 执行所需的原始请求数据。
 *
 * <p>★{@code vocabulary}/{@code aliasSet} 是<b>原始</b> Map 形态（未建 index/未转
 * {@code SemanticTokenKind}）——建索引的逻辑归 core 持有（byte-parity 单一份），
 * 见 {@link ReplayExecutionCore#execute}。
 *
 * <p>★无 {@code toolchainId} 字段：toolchainId 只在 {@code effectiveReplayCapture}
 * 分支才需要（懒取），调用方（aster-api resource）在调
 * {@link ReplayExecutionCore#computeReplayMetadata} 时才传入，与现状调用时机一致。
 *
 * @param aliasesTrusted 结构词别名信任标记，由调用方（aster-api resource）从已验证
 *                        的调用上下文（HMAC 校验结果）派生，非业务输入，不在此类内计算。
 */
public record ReplayExecutionRequest(
        String tenantId,
        String source,
        Object context,
        String functionName,
        String locale,
        Map<String, Object> vocabulary,
        Map<String, List<String>> aliasSet,
        boolean legacyEvaluateSentinel,
        boolean aliasesTrusted,
        boolean trace,
        boolean effectiveReplayCapture) {
}
