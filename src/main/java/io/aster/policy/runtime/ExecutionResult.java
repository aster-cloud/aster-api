package io.aster.policy.runtime;

/**
 * 策略执行结果
 */
public record ExecutionResult(
    boolean success,
    Object result,
    String error,
    long executionTimeMs
) {
    public static ExecutionResult success(Object result, long executionTimeMs) {
        return new ExecutionResult(true, result, null, executionTimeMs);
    }

    public static ExecutionResult failure(String error) {
        return new ExecutionResult(false, null, error, 0);
    }
}
