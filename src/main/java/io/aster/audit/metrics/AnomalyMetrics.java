package io.aster.audit.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Phase 3.8 Task 3: 异常响应执行器 Metrics
 *
 * 提供异常自动回滚相关的监控指标，遵循 BusinessMetrics 模式
 */
@ApplicationScoped
public class AnomalyMetrics {

    private final MeterRegistry registry;

    // 回滚尝试次数
    private final Counter rollbackAttempts;

    // 回滚成功次数
    private final Counter rollbackSuccess;

    // 回滚失败次数
    private final Counter rollbackFailed;

    // 回滚执行耗时
    private final Timer rollbackExecutionDuration;

    public AnomalyMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.rollbackAttempts = Counter.builder("anomaly.rollback.attempts.total")
            .description("Total auto-rollback attempts triggered by anomaly detection")
            .tag("app", "policy-api")
            .register(registry);

        this.rollbackSuccess = Counter.builder("anomaly.rollback.success.total")
            .description("Total successful auto-rollback executions")
            .tag("app", "policy-api")
            .register(registry);

        this.rollbackFailed = Counter.builder("anomaly.rollback.failed.total")
            .description("Total failed auto-rollback executions")
            .tag("app", "policy-api")
            .register(registry);

        this.rollbackExecutionDuration = Timer.builder("anomaly.rollback.execution.duration")
            .description("Auto-rollback execution duration in seconds")
            .tag("app", "policy-api")
            .register(registry);
    }

    /**
     * 记录回滚尝试
     */
    public void recordRollbackAttempt() {
        rollbackAttempts.increment();
    }

    /**
     * 记录回滚成功
     */
    public void recordRollbackSuccess() {
        rollbackSuccess.increment();
    }

    /**
     * 记录回滚失败
     */
    public void recordRollbackFailed() {
        rollbackFailed.increment();
    }

    /**
     * 获取 Timer，用于记录执行耗时
     *
     * 使用方式：
     * <pre>
     * Timer.Sample sample = Timer.start(registry);
     * try {
     *     // 执行回滚逻辑
     * } finally {
     *     sample.stop(anomalyMetrics.getRollbackExecutionTimer());
     * }
     * </pre>
     */
    public Timer getRollbackExecutionTimer() {
        return rollbackExecutionDuration;
    }
}
