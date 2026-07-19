package io.aster.replay.core;

/**
 * replay 执行结果的 core 拥有值类型。
 *
 * <p>★core public API 不得暴露 aster-api 的 {@code DynamicCnlExecutor.ExecutionResult}——
 * 否则 core 为声明签名会反依赖 aster-api，形成模块环。aster-api adapter 负责机械映射。
 */
public record ReplayExecutorResult(
        Object result,
        String moduleName,
        String functionName,
        long executionTimeMs) {
}
