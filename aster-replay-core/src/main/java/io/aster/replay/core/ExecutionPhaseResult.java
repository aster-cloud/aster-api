package io.aster.replay.core;

import aster.truffle.trace.TraceAccess;

/**
 * {@link ReplayExecutionCore#execute} 阶段的产出：执行结果 + trace drain 结果。
 *
 * @param execResult       executor 的执行结果（透传 {@link ReplayExecutor} 返回值）。
 * @param traceDrainResult trace drain 结果；未请求 trace/replayCapture
 *                          （{@code !captureTraceSteps}）时为 {@code null}。
 */
public record ExecutionPhaseResult(
        ReplayExecutorResult execResult,
        TraceAccess.DrainResult traceDrainResult) {
}
