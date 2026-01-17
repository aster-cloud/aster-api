package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 崩溃恢复测试公用基类，封装 workflow/timer 崩溃模拟与轮询查询逻辑。
 */
abstract class CrashRecoveryTestBase {

    @Inject
    protected WorkflowSchedulerService schedulerService;

    @Inject
    protected TimerSchedulerService timerSchedulerService;

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    /**
     * 模拟 workflow 崩溃：清空锁信息并将状态复位为 RUNNING，便于后续恢复流程重新获取锁。
     */
    @Transactional
    void simulateWorkflowCrash(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        if (state == null) {
            throw new IllegalStateException("workflow state not found: " + workflowId);
        }
        state.lockOwner = null;
        state.lockExpiresAt = null;
        state.status = "RUNNING";
        state.persist();
    }

    /**
     * 模拟 timer 崩溃：重置 timer 状态为 PENDING，让调度线程会再次拾取该 timer。
     */
    @Transactional
    void simulateTimerCrash(UUID timerId) {
        Objects.requireNonNull(timerId, "timerId");
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer == null) {
            throw new IllegalStateException("timer not found: " + timerId);
        }
        timer.status = "PENDING";
        timer.persist();
    }

    /**
     * 轮询等待 workflow 状态变化，使用 Thread.sleep(500) 进行简易阻塞等待，超时返回 false。
     */
    @Transactional
    boolean waitForWorkflowStatus(String workflowId, String expectedStatus, Duration timeout) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(timeout, "timeout");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        UUID wfUuid = UUID.fromString(workflowId);
        while (System.currentTimeMillis() < deadline) {
            entityManager.clear();
            WorkflowStateEntity state = WorkflowStateEntity.findById(wfUuid);
            if (state != null && expectedStatus.equals(state.status)) {
                return true;
            }
            if (!sleepQuietly()) {
                return false;
            }
        }
        return false;
    }

    /**
     * 轮询等待 timer 状态变化，遵循 500ms 间隔轮询策略，不依赖 Awaitility。
     */
    @Transactional
    boolean waitForTimerStatus(UUID timerId, String expectedStatus, Duration timeout) {
        Objects.requireNonNull(timerId, "timerId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(timeout, "timeout");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            entityManager.clear();
            WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
            if (timer != null && expectedStatus.equals(timer.status)) {
                return true;
            }
            if (!sleepQuietly()) {
                return false;
            }
        }
        return false;
    }

    /**
     * 查询 workflow 状态，供外部测试断言当前数据库快照。
     */
    @Transactional
    WorkflowStateEntity findWorkflowState(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        return WorkflowStateEntity.findById(UUID.fromString(workflowId));
    }

    /**
     * 查询 timer 明细，辅助验证崩溃恢复过程中 timer 的真实状态。
     */
    @Transactional
    WorkflowTimerEntity findTimerById(UUID timerId) {
        Objects.requireNonNull(timerId, "timerId");
        return WorkflowTimerEntity.findById(timerId);
    }

    /**
     * 创建带有基础事件历史的 workflow，方便测试在任意初始状态下进行崩溃恢复验证。
     */
    @Transactional
    String createWorkflowWithEvents(String initialStatus) {
        Objects.requireNonNull(initialStatus, "initialStatus");

        String workflowId = UUID.randomUUID().toString();
        UUID wfUuid = UUID.fromString(workflowId);

        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = wfUuid;
        state.status = initialStatus;
        state.snapshot = "{}";
        state.lastEventSeq = 0L;
        state.snapshotSeq = 0L;
        state.createdAt = Instant.now();
        state.updatedAt = state.createdAt;
        state.persist();

        long baseSeq = WorkflowEventEntity.count();
        persistEvent(wfUuid, ++baseSeq, WorkflowEvent.Type.WORKFLOW_STARTED, Map.of(
                "status", "STARTED",
                "startedAt", Instant.now().toString()
        ));
        persistEvent(wfUuid, ++baseSeq, WorkflowEvent.Type.STEP_COMPLETED, Map.of(
                "stepId", "bootstrap",
                "status", "COMPLETED",
                "completedAt", Instant.now().toString()
        ));

        state.lastEventSeq = baseSeq;
        state.snapshotSeq = baseSeq;
        state.persist();
        return workflowId;
    }

    /**
     * 统一处理 500ms 轮询睡眠，捕获中断信号并返回 false 供上层快速退出。
     */
    private boolean sleepQuietly() {
        try {
            Thread.sleep(500);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 保存事件实体，确保 sequence/seq/幂等键一致，便于后续重放。
     */
    private void persistEvent(UUID workflowId, long sequence, String eventType, Map<String, Object> payload) {
        WorkflowEventEntity event = new WorkflowEventEntity();
        event.workflowId = workflowId;
        event.sequence = sequence;
        event.seq = sequence;
        event.eventType = eventType;
        event.payload = serializePayload(payload);
        event.occurredAt = Instant.now();
        event.idempotencyKey = workflowId + ":" + eventType + ":" + sequence;
        event.persist();
    }

    /**
     * 将 payload 序列化为 JSON 字符串，序列化失败时抛出运行时异常以便测试及时失败。
     */
    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize payload", e);
        }
    }
}
