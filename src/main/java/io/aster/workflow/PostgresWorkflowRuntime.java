package io.aster.workflow;

import aster.runtime.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.aster.policy.tenant.TenantContext;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL 持久化 Workflow 运行时
 *
 * 实现 durable execution，支持：
 * - 幂等性保证（通过 UNIQUE 约束）
 * - 确定性时间（通过 ReplayDeterministicClock）
 * - 事件重放（从事件流恢复状态）
 * - 高可用（通过 PostgreSQL 持久化）
 */
@ApplicationScoped
public class PostgresWorkflowRuntime implements WorkflowRuntime {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowMetrics metrics;

    @Inject
    PolicyVersionService policyVersionService;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "workflow.result-futures.ttl-hours", defaultValue = "24")
    int ttlHours;

    // 内存中维护的结果 future，用于返回给调用方
    // 实际生产环境可能需要分布式缓存（如 Redis）或轮询机制
    private final Map<String, CompletableFuture<Object>> resultFutures = new ConcurrentHashMap<>();

    /**
     * ThreadLocal 传递当前 workflowId 与 DeterminismContext，避免跨线程污染。
     */
    private static final ThreadLocal<String> currentWorkflowId = new ThreadLocal<>();
    private static final ThreadLocal<DeterminismContext> determinismCache = new ThreadLocal<>();

    /**
     * 全局默认确定性上下文（非 replay 模式）。
     * 当未显式绑定 workflow 时退化到该上下文。
     */
    private final DeterminismContext globalDeterminismContext = new DeterminismContext();

    /**
     * 调度 workflow 执行
     *
     * 实现幂等性：
     * - 如果 idempotencyKey 已存在，返回现有执行句柄
     * - 如果不存在，追加 WorkflowStarted 事件并创建新句柄
     *
     * @param workflowId workflow 唯一标识符
     * @param idempotencyKey 幂等性键（可选）
     * @param metadata workflow 元数据
     * @return 执行句柄
     */
    @Transactional
    @Override
    public ExecutionHandle schedule(String workflowId, String idempotencyKey, WorkflowMetadata metadata) {
        DeterminismContext context = new DeterminismContext();
        setCurrentWorkflowId(workflowId);
        setDeterminismContext(context);
        try {
            UUID wfUuid = UUID.fromString(workflowId);

            // 幂等性检查
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                Optional<WorkflowEventEntity> existing = WorkflowEventEntity.findByIdempotencyKey(wfUuid, idempotencyKey);
                if (existing.isPresent()) {
                    Log.debugf("Workflow %s already started with idempotency key %s", workflowId, idempotencyKey);

                    // 检查 workflow 状态
                    Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(wfUuid);
                    if (stateOpt.isPresent()) {
                        WorkflowStateEntity state = stateOpt.get();
                        if ("COMPLETED".equals(state.status) || "FAILED".equals(state.status)) {
                            // 已完成，返回结果
                            return new CompletedExecutionHandle(workflowId, state.result);
                        }
                    }

                    // 仍在执行中，返回待完成句柄
                    CompletableFuture<Object> future = resultFutures.computeIfAbsent(
                            workflowId,
                            id -> new CompletableFuture<>()
                    );
                    return new PendingExecutionHandle(workflowId, future);
                }
            }

            // 创建 workflow 状态（如果不存在）
            // Phase 4.3: 传递租户ID以确保多租户数据隔离
            String tenantId = tenantContext.isInitialized() ? tenantContext.getCurrentTenant() : null;
            WorkflowStateEntity state = WorkflowStateEntity.getOrCreate(wfUuid, tenantId);

            // Phase 3.4: 标记 workflow 开始时间
            state.markStarted();

            // Phase 3.1: 注入策略版本信息
            enrichPolicyVersion(metadata, state);
            state.persist(); // 保存版本信息

            boolean alreadyStarted = WorkflowEventEntity.hasEvent(wfUuid, WorkflowEvent.Type.WORKFLOW_STARTED);
            if (!alreadyStarted) {
                Map<String, Object> startPayload = Map.of(
                        "metadata", serializeMetadata(metadata),
                        "idempotencyKey", idempotencyKey != null ? idempotencyKey : ""
                );
                eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_STARTED, startPayload);
                metrics.recordWorkflowStarted();
            } else {
                Log.debugf("Workflow %s already recorded WorkflowStarted event, skipping duplicate append", workflowId);
            }

            Log.infof("Scheduled workflow %s with idempotency key %s", workflowId, idempotencyKey);

            // 创建结果 future
            CompletableFuture<Object> future = resultFutures.computeIfAbsent(
                    workflowId,
                    id -> new CompletableFuture<>()
            );

            return new PendingExecutionHandle(workflowId, future);

        } catch (Exception e) {
            Log.errorf(e, "Failed to schedule workflow %s", workflowId);
            throw new RuntimeException("Failed to schedule workflow", e);
        } finally {
            clearDeterminismContext();
            clearCurrentWorkflowId();
        }
    }

    /**
     * 设置当前线程的 workflowId 上下文（Phase 3.6）
     *
     * @param workflowId workflow 唯一标识符
     */
    public void setCurrentWorkflowId(String workflowId) {
        currentWorkflowId.set(workflowId);
    }

    /**
     * 清除当前线程的 workflowId 上下文（Phase 3.6）
     */
    public void clearCurrentWorkflowId() {
        currentWorkflowId.remove();
    }

    /**
     * 为当前线程绑定 DeterminismContext。
     *
     * @param context 确定性上下文
     */
    public void setDeterminismContext(DeterminismContext context) {
        determinismCache.set(context);
    }

    /**
     * 清理当前线程绑定的 DeterminismContext。
     */
    public void clearDeterminismContext() {
        determinismCache.remove();
    }

    /**
     * 获取当前线程绑定的确定性上下文。
     *
     * @return 当无上下文绑定时返回全局默认上下文
     */
    public DeterminismContext getDeterminismContext() {
        DeterminismContext context = determinismCache.get();
        if (context == null) {
            return globalDeterminismContext;
        }
        return context;
    }

    /**
     * 兼容旧接口：返回 DeterminismContext 提供的时钟实例。
     *
     * @return 确定性时钟实例
     */
    @Override
    @Deprecated
    public DeterministicClock getClock() {
        return getDeterminismContext().clock();
    }

    /**
     * 获取事件存储
     *
     * @return 事件存储实例
     */
    @Override
    public EventStore getEventStore() {
        return eventStore;
    }

    /**
     * 关闭运行时（Phase 3.6 改造）
     *
     * 取消所有待完成的 future，清理所有 clock 缓存。
     */
    @Override
    public void shutdown() {
        Log.info("Shutting down PostgresWorkflowRuntime");
        resultFutures.values().forEach(future -> future.cancel(true));
        resultFutures.clear();
    }

    /**
     * 完成 workflow 执行（Phase 3.6 改造）
     *
     * 由调度器在 workflow 完成时调用，设置结果并完成 future。
     * 同时持久化 clock_times 并清理 clock 缓存。
     *
     * @param workflowId workflow 唯一标识符
     * @param result 执行结果
     */
    public void completeWorkflow(String workflowId, Object result) {
        DeterminismContext context = determinismCache.get();
        persistDeterminismSnapshot(workflowId, context);

        // 原有 resultFutures 处理逻辑
        CompletableFuture<Object> future = resultFutures.remove(workflowId);
        if (future != null) {
            future.complete(result);
        }

        clearDeterminismContext();
        clearCurrentWorkflowId();
    }

    /**
     * 使 workflow 执行失败（Phase 3.6 改造）
     *
     * 由调度器在 workflow 失败时调用，设置异常并完成 future。
     * 同时持久化 clock_times 并清理 clock 缓存。
     *
     * @param workflowId workflow 唯一标识符
     * @param error 失败原因
     */
    public void failWorkflow(String workflowId, Throwable error) {
        DeterminismContext context = determinismCache.get();
        persistDeterminismSnapshot(workflowId, context);

        // 原有 resultFutures 处理逻辑
        CompletableFuture<Object> future = resultFutures.remove(workflowId);
        if (future != null) {
            future.completeExceptionally(error);
        }

        clearDeterminismContext();
        clearCurrentWorkflowId();
    }

    /**
     * 获取结果 future（用于调度器）
     *
     * @param workflowId workflow 唯一标识符
     * @return 结果 future
     */
    public CompletableFuture<Object> getResultFuture(String workflowId) {
        return resultFutures.computeIfAbsent(workflowId, id -> new CompletableFuture<>());
    }

    /**
     * 定时清理过期的 resultFutures 和 clock 缓存（Phase 3.6 改造）
     *
     * 防止内存泄漏：清理超过 TTL 的 workflow 结果 future 和 clock 实例。
     * 默认每小时执行一次，TTL 默认 24 小时。
     */
    @Scheduled(every = "1h")
    void cleanupExpiredFutures() {
        Instant threshold = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        int removedFutures = 0;

        // 清理 resultFutures
        for (Map.Entry<String, CompletableFuture<Object>> entry : resultFutures.entrySet()) {
            String workflowId = entry.getKey();
            try {
                Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId));

                // 清理条件：
                // 1. workflow 状态不存在
                // 2. workflow 已完成或已失败且已超过 TTL
                // 注意：仍在运行的 workflow 不应被清理，即使超过 TTL
                boolean shouldCleanup = false;
                if (stateOpt.isEmpty()) {
                    shouldCleanup = true;
                } else {
                    WorkflowStateEntity state = stateOpt.get();
                    boolean isTerminal = "COMPLETED".equals(state.status) || "FAILED".equals(state.status);
                    boolean isExpired = state.updatedAt.isBefore(threshold);
                    shouldCleanup = isTerminal && isExpired;
                }

                if (shouldCleanup) {
                    // 先通知等待方 workflow 已过期，再移除
                    CompletableFuture<Object> future = resultFutures.remove(workflowId);
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(new WorkflowExpiredException(
                                "Workflow " + workflowId + " expired after " + ttlHours + " hours"));
                    }
                    removedFutures++;
                }
            } catch (Exception e) {
                Log.warnf(e, "Failed to check workflow %s during cleanup, skipping", workflowId);
            }
        }

        if (removedFutures > 0) {
            Log.infof("Cleaned up %d expired workflow result futures", removedFutures);
            metrics.recordResultFuturesCleaned(removedFutures);
        }
    }

    /**
     * Workflow 过期异常
     *
     * 当 workflow 结果 future 因超过 TTL 被清理时抛出
     */
    public static class WorkflowExpiredException extends RuntimeException {
        public WorkflowExpiredException(String message) {
            super(message);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 序列化元数据为 JSON 字符串
     */
    private String serializeMetadata(WorkflowMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }

    /**
     * 持久化确定性快照。
     *
     * @param workflowId workflow 唯一标识符
     * @param context 当前线程绑定的确定性上下文
     */
    private void persistDeterminismSnapshot(String workflowId, DeterminismContext context) {
        if (context == null) {
            return;
        }
        try {
            String snapshotJson = serializeDeterminismSnapshot(context);
            if (snapshotJson == null) {
                return;
            }
            Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId));
            if (stateOpt.isPresent()) {
                WorkflowStateEntity state = stateOpt.get();
                state.clockTimes = snapshotJson;
                state.persist();
            }
        } catch (Exception e) {
            Log.warnf(e, "Failed to persist determinism snapshot for workflow %s, continuing", workflowId);
        }
    }

    /**
     * 序列化 DeterminismContext 为 JSONB 字符串（Phase 0 Task 1.4）
     *
     * @param context 确定性上下文
     * @return JSONB 字符串，失败或无数据时返回 null
     */
    public static String serializeDeterminismSnapshot(DeterminismContext context) {
        if (context == null) {
            return null;
        }
        try {
            DeterminismSnapshot snapshot = DeterminismSnapshot.from(
                    context.clock(),
                    context.uuid(),
                    context.random()
            );
            if (snapshot.isEmpty()) {
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            Log.errorf(e, "Failed to serialize determinism snapshot, skipping persistence");
            return null;
        }
    }

    /**
     * 注入策略版本信息（Phase 3.1）
     *
     * 如果元数据包含 policyId，自动查询当前活跃版本并注入到元数据中。
     * 用于审计追踪：记录每次 workflow 执行使用的具体策略版本。
     *
     * @param metadata workflow 元数据
     * @param state workflow 状态实体（用于记录版本信息）
     */
    private void enrichPolicyVersion(WorkflowMetadata metadata, WorkflowStateEntity state) {
        // 检查元数据是否包含 policyId
        String policyId = metadata.get(WorkflowMetadata.Keys.POLICY_ID, String.class);
        if (policyId == null || policyId.isEmpty()) {
            return; // 无策略信息，跳过
        }

        try {
            // 查询当前活跃版本
            PolicyVersion activeVersion = policyVersionService.getActiveVersion(policyId);

            if (activeVersion != null) {
                // 注入到元数据
                metadata.setPolicyVersion(policyId, activeVersion.version, activeVersion.id);

                // 记录到 workflow_state
                state.policyVersionId = activeVersion.id;
                state.policyActivatedAt = Instant.now();

                Log.debugf("Enriched workflow with policy version: %s v%d (id=%d)",
                        policyId, activeVersion.version, activeVersion.id);
            } else {
                Log.warnf("No active version found for policyId=%s, workflow will proceed without version tracking", policyId);
            }
        } catch (Exception e) {
            // 版本查询失败不影响 workflow 执行，仅记录警告
            Log.warnf(e, "Failed to enrich policy version for policyId=%s, workflow will proceed without version tracking", policyId);
        }
    }
}
