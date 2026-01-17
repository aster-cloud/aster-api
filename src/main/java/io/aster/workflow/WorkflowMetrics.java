package io.aster.workflow;

import io.micrometer.core.instrument.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workflow 执行性能指标
 *
 * 提供以下 Prometheus 指标：
 * - workflow_started_total: workflow 启动总数
 * - workflow_completed_total: workflow 完成总数
 * - workflow_failed_total: workflow 失败总数
 * - workflow_compensations_triggered_total: 补偿触发总数
 * - workflow_pending: 待处理 workflow 数量
 * - workflow_execution_duration_seconds: workflow 执行耗时
 */
@ApplicationScoped
public class WorkflowMetrics {

    @Inject
    MeterRegistry registry;

    private Counter workflowsStarted;
    private Counter workflowsCompleted;
    private Counter workflowsFailed;
    private Counter compensationsTriggered;
    private Counter compensationsCompleted;
    private Counter compensationsFailed;
    private Counter resultFuturesCleaned;
    private Timer executionDuration;

    // 用于 pending workflows gauge 的原子计数器
    private final AtomicLong pendingCount = new AtomicLong(0);

    /**
     * 初始化指标
     */
    void onStart(@Observes StartupEvent ev) {
        // 启动计数器
        workflowsStarted = Counter.builder("workflow.started.total")
                .description("Total workflows started")
                .register(registry);

        workflowsCompleted = Counter.builder("workflow.completed.total")
                .description("Total workflows completed successfully")
                .register(registry);

        workflowsFailed = Counter.builder("workflow.failed.total")
                .description("Total workflows failed")
                .register(registry);

        compensationsTriggered = Counter.builder("workflow.compensations.triggered.total")
                .description("Total compensations triggered")
                .register(registry);

        compensationsCompleted = Counter.builder("workflow.compensations.completed.total")
                .description("Total compensations completed successfully")
                .register(registry);

        compensationsFailed = Counter.builder("workflow.compensations.failed.total")
                .description("Total compensations failed")
                .register(registry);

        resultFuturesCleaned = Counter.builder("workflow.result_futures.cleaned.total")
                .description("Total result futures cleaned up due to TTL expiration")
                .register(registry);

        // 执行耗时直方图
        executionDuration = Timer.builder("workflow.execution.duration")
                .description("Workflow execution duration in seconds")
                .register(registry);

        // 待处理 workflow 数量 (实时)
        Gauge.builder("workflow.pending", pendingCount, AtomicLong::get)
                .description("Number of workflows currently pending (READY + RUNNING)")
                .register(registry);

        // 按状态分类的 workflow 计数
        Gauge.builder("workflow.by.status", () -> WorkflowStateEntity.countByStatus("READY"))
                .tag("status", "READY")
                .description("Workflows in READY status")
                .register(registry);

        Gauge.builder("workflow.by.status", () -> WorkflowStateEntity.countByStatus("RUNNING"))
                .tag("status", "RUNNING")
                .description("Workflows in RUNNING status")
                .register(registry);

        Gauge.builder("workflow.by.status", () -> WorkflowStateEntity.countByStatus("COMPENSATING"))
                .tag("status", "COMPENSATING")
                .description("Workflows in COMPENSATING status")
                .register(registry);
    }

    /**
     * 记录 workflow 启动
     */
    public void recordWorkflowStarted() {
        workflowsStarted.increment();
        pendingCount.incrementAndGet();
    }

    /**
     * 记录 workflow 完成
     *
     * @param duration 执行耗时
     */
    public void recordWorkflowCompleted(Duration duration) {
        workflowsCompleted.increment();
        executionDuration.record(duration);
        pendingCount.decrementAndGet();
    }

    /**
     * 记录 workflow 失败
     */
    public void recordWorkflowFailed() {
        workflowsFailed.increment();
        pendingCount.decrementAndGet();
    }

    /**
     * 记录补偿触发
     *
     * @param stepCount 触发补偿的 step 数量
     */
    public void recordCompensationTriggered(int stepCount) {
        compensationsTriggered.increment(stepCount);
    }

    /**
     * 记录补偿完成
     */
    public void recordCompensationCompleted() {
        compensationsCompleted.increment();
    }

    /**
     * 记录补偿失败
     */
    public void recordCompensationFailed() {
        compensationsFailed.increment();
    }

    /**
     * 更新 pending workflow 计数
     *
     * 从数据库实时查询并更新。
     */
    public void refreshPendingCount() {
        long count = WorkflowStateEntity.countByStatus("READY") +
                     WorkflowStateEntity.countByStatus("RUNNING");
        pendingCount.set(count);
    }

    /**
     * 记录 resultFutures 清理
     *
     * @param count 清理的 future 数量
     */
    public void recordResultFuturesCleaned(int count) {
        resultFuturesCleaned.increment(count);
    }
}
