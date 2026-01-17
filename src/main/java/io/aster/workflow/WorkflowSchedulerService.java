package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import aster.truffle.runtime.AsyncTaskRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aster.monitoring.BusinessMetrics;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * PostgreSQL-backed Workflow 调度器服务
 *
 * 核心特性：
 * - LISTEN/NOTIFY 事件驱动调度（低延迟）
 * - SELECT FOR UPDATE SKIP LOCKED 分布式锁（避免冲突）
 * - 定时器轮询（处理 orphan timers）
 * - 事件重放恢复状态
 */
@ApplicationScoped
public class WorkflowSchedulerService {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SagaCompensationExecutor compensationExecutor;

    @Inject
    BusinessMetrics businessMetrics;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    String dbKind;

    private ExecutorService executorService;
    private ScheduledExecutorService timerPollingService;
    private volatile boolean running = false;
    private final AsyncTaskRegistry registry = new AsyncTaskRegistry();

    // Worker 标识符：调度器级别标识，不处于 workflow 上下文，保持 UUID 随机值即可
    private final String workerId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * 服务启动
     */
    void onStart(@Observes StartupEvent ev) {
        Log.infof("Starting WorkflowSchedulerService (worker=%s)", workerId);

        // 创建工作线程池（可配置）
        int workerCount = Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool(workerCount);

        // 启动定时器轮询服务
        timerPollingService = Executors.newScheduledThreadPool(1);

        // 注意：pollTimers() 已禁用，timer 调度由 TimerSchedulerService 统一管理
        // TimerSchedulerService 会处理所有定时器状态转换（PENDING → EXECUTING → COMPLETED/PENDING）
        // WorkflowSchedulerService 不再参与 timer 调度，避免竞态条件

        timerPollingService.scheduleAtFixedRate(
                this::pollReadyWorkflows,
                0,
                1,
                TimeUnit.SECONDS
        );

        // 启动延迟任务轮询线程
        // TODO: registry.startPolling() - method removed, need to reimplement

        running = true;
        Log.info("WorkflowSchedulerService started");
    }

    /**
     * 服务关闭
     */
    void onStop(@Observes ShutdownEvent ev) {
        Log.info("Stopping WorkflowSchedulerService");
        running = false;

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }

        if (timerPollingService != null) {
            timerPollingService.shutdown();
            try {
                if (!timerPollingService.awaitTermination(5, TimeUnit.SECONDS)) {
                    timerPollingService.shutdownNow();
                }
            } catch (InterruptedException e) {
                timerPollingService.shutdownNow();
            }
        }

        // 停止延迟任务轮询线程
        // TODO: registry.stopPolling() - method removed, need to reimplement

        Log.info("WorkflowSchedulerService stopped");
    }

    /**
     * 轮询就绪的 workflow
     *
     * 使用 SELECT FOR UPDATE SKIP LOCKED 避免多 worker 冲突。
     * 实际生产环境应使用 LISTEN/NOTIFY 代替轮询。
     */
    private void pollReadyWorkflows() {
        if (!running) return;

        try {
            List<WorkflowStateEntity> readyWorkflows = findReadyWorkflows(10);

            for (WorkflowStateEntity state : readyWorkflows) {
                executorService.submit(() -> processWorkflow(state.workflowId.toString()));
            }

        } catch (Exception e) {
            Log.errorf(e, "Error polling ready workflows");
        }
    }

    /**
     * 查找就绪的 workflow（使用 SKIP LOCKED）
     *
     * @param limit 最大返回数量
     * @return 就绪的 workflow 列表
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<WorkflowStateEntity> findReadyWorkflows(int limit) {
        // 使用原生 SQL 查询以支持 PostgreSQL 的 FOR UPDATE SKIP LOCKED 语法
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM workflow_state " +
                "WHERE status IN ('READY', 'RUNNING') " +
                "AND (lock_expires_at IS NULL OR lock_expires_at < :now) " +
                "ORDER BY created_at"
        );
        if (supportsSkipLocked()) {
            sql.append(" FOR UPDATE SKIP LOCKED");
        }
        sql.append(" LIMIT :limit");

        return entityManager.createNativeQuery(sql.toString(), WorkflowStateEntity.class)
        .setParameter("now", Instant.now())
        .setParameter("limit", limit)
        .getResultList();
    }

    private boolean supportsSkipLocked() {
        return !"h2".equalsIgnoreCase(dbKind);
    }

    /**
     * 处理单个 workflow
     *
     * @param workflowId workflow 唯一标识符
     */
    @Transactional
    public void processWorkflow(String workflowId) {
        // Phase 3.6: 设置 ThreadLocal 上下文传递 workflowId
        workflowRuntime.setCurrentWorkflowId(workflowId);
        DeterminismContext context = new DeterminismContext();
        workflowRuntime.setDeterminismContext(context);
        try {
            UUID wfUuid = UUID.fromString(workflowId);

            // 查找并锁定 workflow 状态
            Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(wfUuid);
            if (stateOpt.isEmpty()) {
                Log.warnf("Workflow %s not found", workflowId);
                return;
            }

            WorkflowStateEntity state = stateOpt.get();

            // Phase 3.6: 加载并激活 replay 模式
            if (state.clockTimes != null && !state.clockTimes.isBlank()) {
                DeterminismSnapshot snapshot = deserializeDeterminismSnapshot(state.clockTimes);
                if (snapshot != null) {
                    snapshot.applyTo(context.clock(), context.uuid(), context.random());
                    int clockCount = snapshot.getClockTimes() != null
                            ? snapshot.getClockTimes().size()
                            : 0;
                    Log.infof("Workflow %s entered replay mode with %d clock decisions",
                            workflowId, clockCount);
                }
            }

            // 检查是否已被其他 worker 处理
            if (state.lockOwner != null && state.lockExpiresAt != null &&
                state.lockExpiresAt.isAfter(Instant.now())) {
                Log.debugf("Workflow %s is locked by another worker", workflowId);
                return;
            }

            String previousStatus = state.status;

            // 加锁
            state.lockOwner = workerId;
            state.lockExpiresAt = Instant.now().plusSeconds(60);
            if ("COMPENSATING".equals(previousStatus) || "COMPENSATED".equals(previousStatus)
                    || "COMPENSATION_FAILED".equals(previousStatus)) {
                // 保持补偿相关状态，避免被并发 worker 误改回 RUNNING
                state.status = previousStatus;
            } else {
                state.status = "RUNNING";
            }
            state.persist();

            Log.infof("Processing workflow %s (worker=%s)", workflowId, workerId);

            // Snapshot 优化：从最后的 snapshot 开始重放事件，而非从 0
            long fromSeq = 0;
            Optional<aster.runtime.workflow.WorkflowSnapshot> snapshot = eventStore.getLatestSnapshot(workflowId);
            if (snapshot.isPresent()) {
                fromSeq = snapshot.get().getEventSeq() + 1;  // 从 snapshot 下一个事件开始
                Log.infof("Workflow %s replaying from snapshot seq %d (skipped %d events)",
                        workflowId, snapshot.get().getEventSeq(), fromSeq);
            } else {
                Log.debugf("Workflow %s no snapshot found, replaying from event 0", workflowId);
            }

            // 获取事件历史（从 snapshot 之后开始）
            List<WorkflowEvent> events = eventStore.getEvents(workflowId, fromSeq);

            // 检查 workflow 状态
            boolean completed = events.stream()
                    .anyMatch(e -> "WorkflowCompleted".equals(e.getEventType()));
            boolean failed = events.stream()
                    .anyMatch(e -> "WorkflowFailed".equals(e.getEventType()));
            boolean stepFailed = events.stream()
                    .anyMatch(e -> "StepFailed".equals(e.getEventType()));
            boolean compensating = "COMPENSATING".equals(previousStatus);
            boolean compensationFailed = compensationExecutor.hasCompensationFailure(events);
            boolean allCompensationsCompleted = compensationExecutor.areAllCompensationsCompleted(events);

            // 处理 step 失败 -> 触发补偿
            if (stepFailed && !compensating && !compensationFailed) {
                Optional<WorkflowEvent> failedEvent = events.stream()
                        .filter(e -> "StepFailed".equals(e.getEventType()))
                        .findFirst();

                if (failedEvent.isPresent()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) failedEvent.get().getPayload();
                    String failedStepId = (String) payload.get("stepId");
                    String errorMessage = (String) payload.getOrDefault("error", "Unknown error");

                    compensationExecutor.handleStepFailure(
                            workflowId,
                            failedStepId,
                            new RuntimeException(errorMessage)
                    );

                    state.status = "COMPENSATING";
                    state.persist();
                    Log.infof("Triggered compensation for workflow %s", workflowId);
                }
            }

            // 处理补偿完成
            if (allCompensationsCompleted) {
                state.status = "COMPENSATED";
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();

                workflowRuntime.completeWorkflow(workflowId, null);
                businessMetrics.recordWorkflowExecution();
                Log.infof("All compensations completed for workflow %s", workflowId);
                return;
            }

            // 处理补偿失败
            if (compensationFailed) {
                state.markCompleted("COMPENSATION_FAILED");
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();

                workflowRuntime.failWorkflow(workflowId,
                        new RuntimeException("Compensation failed, requires manual intervention"));
                Log.errorf("Compensation failed for workflow %s, requires manual intervention", workflowId);
                return;
            }

            // 处理正常完成
            if (completed) {
                state.markCompleted("COMPLETED");
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();

                // 通知 runtime 完成
                Object result = events.stream()
                        .filter(e -> "WorkflowCompleted".equals(e.getEventType()))
                        .map(WorkflowEvent::getPayload)
                        .findFirst()
                        .orElse(null);
                workflowRuntime.completeWorkflow(workflowId, result);
                businessMetrics.recordWorkflowExecution();

                Log.infof("Workflow %s completed", workflowId);

            } else if (failed) {
                state.markCompleted("FAILED");
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();

                // 通知 runtime 失败
                workflowRuntime.failWorkflow(workflowId, new RuntimeException("Workflow failed"));

                Log.infof("Workflow %s failed", workflowId);

            } else {
                // 仍在执行中，释放锁等待下次调度
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();

                Log.debugf("Workflow %s still running, will retry later", workflowId);
            }

        } catch (Exception e) {
            Log.errorf(e, "Error processing workflow %s", workflowId);
            // 释放锁以便重试
            try {
                releaseWorkflowLock(workflowId);
            } catch (Exception ex) {
                Log.errorf(ex, "Failed to release lock for workflow %s", workflowId);
            }
        } finally {
            // Phase 3.6: 清理 ThreadLocal 上下文防止内存泄漏
            workflowRuntime.clearDeterminismContext();
            workflowRuntime.clearCurrentWorkflowId();
        }
    }

    /**
     * 反序列化 clock_times JSONB 字符串（Phase 0 Task 1.3）
     *
     * @param clockTimesJson JSON 文本
     * @return DeterminismSnapshot 实例或 null
     */
    private DeterminismSnapshot deserializeDeterminismSnapshot(String clockTimesJson) {
        if (clockTimesJson == null || clockTimesJson.isBlank()) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.readValue(clockTimesJson, DeterminismSnapshot.class);
        } catch (Exception e) {
            Log.warnf(e, "Failed to deserialize DeterminismSnapshot, degrading to non-replay mode");
            return null;
        }
    }

    /**
     * 轮询待触发的定时器
     */
    @Transactional
    public void pollTimers() {
        if (!running) return;

        try {
            List<WorkflowTimerEntity> firedTimers = WorkflowTimerEntity.findFiredTimers(100);

            for (WorkflowTimerEntity timer : firedTimers) {
                try {
                    // 追加 TimerFired 事件
                    eventStore.appendEvent(
                            timer.workflowId.toString(),
                            WorkflowEvent.Type.TIMER_FIRED,
                            timer.payload
                    );

                    // 标记定时器已触发
                    timer.status = "FIRED";
                    timer.persist();

                    Log.debugf("Timer %s fired for workflow %s", timer.timerId, timer.workflowId);

                } catch (Exception e) {
                    Log.errorf(e, "Error firing timer %s", timer.timerId);
                }
            }

        } catch (Exception e) {
            Log.errorf(e, "Error polling timers");
        }
    }

    /**
     * 释放 workflow 锁
     *
     * @param workflowId workflow 唯一标识符
     */
    @Transactional
    public void releaseWorkflowLock(String workflowId) {
        UUID wfUuid = UUID.fromString(workflowId);
        Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(wfUuid);
        if (stateOpt.isPresent()) {
            WorkflowStateEntity state = stateOpt.get();
            if (workerId.equals(state.lockOwner)) {
                state.lockOwner = null;
                state.lockExpiresAt = null;
                state.persist();
            }
        }
    }

    /**
     * 获取结果 future（用于 ExecutionHandle）
     *
     * @param workflowId workflow 唯一标识符
     * @return 结果 future
     */
    public CompletableFuture<Object> getResultFuture(String workflowId) {
        return workflowRuntime.getResultFuture(workflowId);
    }

    /**
     * Replay workflow（Phase 3.8）
     *
     * 显式 replay 语义，用于异常验证场景。与 processWorkflow() 的区别：
     * - 验证 clock_times 必须存在（否则无法 replay）
     * - 重置状态为 READY 触发重新执行
     * - 返回 Uni 支持响应式调用
     * - 超时控制（5分钟）
     *
     * 注意：clock_times 加载逻辑已在 processWorkflow() 中实现（Phase 3.6），
     * 此方法复用该机制，无需重复实现。
     *
     * @param workflowId workflow 唯一标识符
     * @return 重放后的 workflow 状态
     * @throws IllegalArgumentException  Workflow 不存在
     * @throws IllegalStateException     缺少 clock_times，无法 replay
     * @throws TimeoutException          Replay 执行超时
     */
    @Transactional
    public Uni<WorkflowStateEntity> replayWorkflow(UUID workflowId) {
        // 1. 查询 workflow 状态
        Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(workflowId);
        if (stateOpt.isEmpty()) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("Workflow 不存在: " + workflowId)
            );
        }

        WorkflowStateEntity state = stateOpt.get();

        // 2. 验证 clock_times 存在
        if (state.clockTimes == null || state.clockTimes.isBlank()) {
            return Uni.createFrom().failure(new IllegalStateException(
                "Workflow " + workflowId + " 没有 clock_times，无法 replay"
            ));
        }

        Log.infof("开始 replay workflow %s，clock_times 长度: %d",
            workflowId, state.clockTimes.length());

        // 3. 重置状态为 READY（准备重放）
        state.status = "READY";
        state.persist();

        // 4. 异步调用 processWorkflow（clock_times 会自动加载）
        return Uni.createFrom().item(() -> {
            try {
                processWorkflow(workflowId.toString());
                // 重新查询最新状态
                return WorkflowStateEntity.findByWorkflowId(workflowId).orElse(state);
            } catch (Exception e) {
                throw new RuntimeException("Replay 执行失败: " + e.getMessage(), e);
            }
        })
        .ifNoItem().after(Duration.ofMinutes(5))  // 超时控制
        .failWith(() -> new TimeoutException("Replay 超时: " + workflowId));
    }

    /**
     * 恢复 workflow 执行（从当前状态继续）
     *
     * 用于 timer 触发后恢复 workflow
     *
     * @param workflowId workflow 唯一标识符
     */
    @Transactional
    public void resumeWorkflow(String workflowId) {
        UUID wfUuid = UUID.fromString(workflowId);
        Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(wfUuid);

        if (stateOpt.isEmpty()) {
            Log.warnf("Cannot resume workflow %s: not found", workflowId);
            return;
        }

        WorkflowStateEntity state = stateOpt.get();

        // 只恢复暂停或就绪状态的 workflow
        if ("PAUSED".equals(state.status) || "READY".equals(state.status)) {
            state.status = "READY";
            state.lockOwner = null; // 释放锁
            state.lockExpiresAt = null;
            state.persist();

            Log.infof("Resuming workflow %s from status %s", workflowId, state.status);

            // 异步调度执行
            executorService.submit(() -> {
                try {
                    processWorkflow(workflowId);
                } catch (Exception e) {
                    Log.errorf(e, "Error resuming workflow %s", workflowId);
                }
            });
        } else {
            Log.debugf("Workflow %s is in status %s, cannot resume", workflowId, state.status);
        }
    }

    /**
     * 恢复 workflow 特定 step 的执行
     *
     * 用于 timer 触发特定 step 继续执行
     *
     * @param workflowId workflow 唯一标识符
     * @param stepId     要恢复的 step 标识符
     */
    @Transactional
    public void resumeWorkflowStep(String workflowId, String stepId) {
        UUID wfUuid = UUID.fromString(workflowId);
        Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(wfUuid);

        if (stateOpt.isEmpty()) {
            Log.warnf("Cannot resume workflow %s step %s: workflow not found", workflowId, stepId);
            return;
        }

        WorkflowStateEntity state = stateOpt.get();

        // 恢复执行（不设置 currentStep 因为该字段不存在，workflow 内部会通过事件重放确定当前位置）
        state.status = "READY";
        state.lockOwner = null; // 释放锁
        state.lockExpiresAt = null;
        state.persist();

        Log.infof("Resuming workflow %s at step %s", workflowId, stepId);

        // 追加 TIMER_TRIGGERED 事件
        // 注意：payload 仅包含 stepId，不包含 timestamp，确保重试时 idempotency key 一致
        try {
            eventStore.appendEvent(
                workflowId,
                "TIMER_TRIGGERED",
                String.format("{\"stepId\":\"%s\"}", stepId)
            );
        } catch (Exception e) {
            Log.warnf(e, "Failed to append TIMER_TRIGGERED event for workflow %s step %s", workflowId, stepId);
        }

        // 异步调度执行
        executorService.submit(() -> {
            try {
                processWorkflow(workflowId);
            } catch (Exception e) {
                Log.errorf(e, "Error resuming workflow %s at step %s", workflowId, stepId, e);
            }
        });
    }
}
