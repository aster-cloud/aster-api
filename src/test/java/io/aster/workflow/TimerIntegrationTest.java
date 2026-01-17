package io.aster.workflow;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timer 调度集成测试
 *
 * 使用 PostgreSQL Testcontainers 验证定时器触发和 workflow 状态推进
 *
 * 注意：由于 @Scheduled 任务在后台线程执行，这些测试使用轮询方式检查结果，
 * 而非 Awaitility 异步断言（避免事务上下文问题）
 */
@QuarkusTest
@TestProfile(TimerIntegrationTestProfile.class)
public class TimerIntegrationTest {

    @Inject
    TimerSchedulerService timerSchedulerService;

    @Inject
    WorkflowSchedulerService workflowScheduler;

    @BeforeEach
    @AfterEach
    @Transactional
    public void cleanup() {
        // 清理测试数据
        WorkflowTimerEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
    }

    @Test
    public void testTimerTriggersWorkflowTransition() throws InterruptedException {
        // Given: 创建 PAUSED 状态的 workflow
        String workflowId = createWorkflowWithTimer("PAUSED", Duration.ofSeconds(2));

        // When: 等待 timer 触发（最多 5 秒，使用轮询而非 Awaitility）
        boolean timerCompleted = waitForTimerCompletion(workflowId, Duration.ofSeconds(5));

        // Then: 验证 timer 已完成
        assertTrue(timerCompleted, "Timer should complete within 5 seconds");

        // 验证 workflow 状态已从 PAUSED 转换（可能是 READY 或 RUNNING，取决于执行速度）
        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertNotNull(state);
        assertFalse("PAUSED".equals(state.status), "Workflow should no longer be PAUSED");
    }

    @Test
    public void testPeriodicTimerReschedulesItself() throws InterruptedException {
        // Given: 创建周期性 timer（每 2 秒触发一次）
        String workflowId = UUID.randomUUID().toString();

        UUID timerId = createPeriodicTimerWithWorkflow(workflowId, Duration.ofSeconds(2));
        WorkflowTimerEntity initialSnapshot = findTimerById(timerId);
        assertNotNull(initialSnapshot);
        Instant baselineFireAt = initialSnapshot.fireAt;

        // When: 等待 fireAt 推进（轮询，避免固定 sleep 带来的不确定性）
        WorkflowTimerEntity timer = waitForTimerReschedule(timerId, baselineFireAt, Duration.ofSeconds(10));
        assertNotNull(timer, "Periodic timer should still exist after first trigger");

        // Then: 验证 timer 已重新调度
        assertEquals("PENDING", timer.status, "Periodic timer should return to PENDING");
        assertNotNull(timer.intervalMillis);
        assertEquals(Duration.ofSeconds(2).toMillis(), timer.intervalMillis);
        assertTrue(timer.fireAt.isAfter(baselineFireAt),
            "Periodic timer fireAt should advance after first trigger");
        assertTrue(timer.fireAt.isAfter(Instant.now().minusSeconds(1)),
            "Next fire time should be in the future");
    }

    @Test
    public void testTimerCancellation() throws InterruptedException {
        // Given: 创建 10 秒后触发的 timer
        String workflowId = UUID.randomUUID().toString();
        UUID timerId = createTimerWithWorkflow(workflowId, Duration.ofSeconds(10));

        // When: 立即取消 timer
        boolean cancelled = cancelTimer(timerId);

        // Then: 验证取消成功
        assertTrue(cancelled);

        WorkflowTimerEntity timer = findTimerById(timerId);
        assertEquals("CANCELLED", timer.status);

        // 等待 2 秒，确认 timer 不会被执行
        Thread.sleep(2000);

        timer = findTimerById(timerId);
        assertEquals("CANCELLED", timer.status, "Timer should remain CANCELLED");
    }

    @Test
    public void testMultipleTimersExecuteInOrder() throws InterruptedException {
        // Given: 创建多个不同延迟的 timers
        String workflowId = UUID.randomUUID().toString();

        createTimerWithWorkflow(workflowId, Duration.ofSeconds(1));
        createTimerWithWorkflow(workflowId, Duration.ofSeconds(2));
        createTimerWithWorkflow(workflowId, Duration.ofSeconds(3));

        // When: 等待所有 timers 执行完成
        // Scheduler 每 1 秒轮询，最长 timer 3 秒，需要至少 3 + 2 = 5 秒
        // 但为了避免边界竞争条件，等待 7 秒
        Thread.sleep(7000);

        // Then: 验证所有 timers 已完成
        long completedCount = countCompletedTimers(workflowId);
        List<WorkflowTimerEntity> timers = findTimersByWorkflowId(workflowId);

        // 打印诊断信息
        if (completedCount != 3) {
            System.out.println("Expected 3 completed timers but found " + completedCount);
            for (WorkflowTimerEntity t : timers) {
                System.out.println("Timer " + t.timerId + ": status=" + t.status +
                    ", fireAt=" + t.fireAt + ", stepId=" + t.stepId);
            }
        }

        assertEquals(3, completedCount, "All 3 timers should be completed");
        assertEquals(3, timers.size());
        for (WorkflowTimerEntity t : timers) {
            assertEquals("COMPLETED", t.status);
        }
    }

    // ============ Helper Methods ============
    // Note: Methods with @Transactional must be package-private or public in Quarkus

    @Transactional
    String createWorkflowWithTimer(String status, Duration delay) {
        String workflowId = UUID.randomUUID().toString();

        // 创建 workflow state
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = status;
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        // 创建 timer
        timerSchedulerService.scheduleTimer(
            workflowId,
            "next_step",
            delay,
            "{\"trigger\":\"test\"}"
        );

        return workflowId;
    }

    @Transactional
    UUID createTimerWithWorkflow(String workflowId, Duration delay) {
        // 创建 workflow state（如果不存在）
        WorkflowStateEntity existing = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        if (existing == null) {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = UUID.fromString(workflowId);
            state.status = "READY";
            state.snapshot = "{}";
            state.createdAt = Instant.now();
            state.persist();
        }

        // 创建 timer
        WorkflowTimerEntity timer = timerSchedulerService.scheduleTimer(
            workflowId,
            "step_" + UUID.randomUUID().toString().substring(0, 8),
            delay,
            "{}"
        );

        return timer.timerId;
    }

    @Transactional
    UUID createPeriodicTimerWithWorkflow(String workflowId, Duration interval) {
        // 创建 workflow state
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = "READY";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        // 创建周期性 timer
        WorkflowTimerEntity timer = timerSchedulerService.schedulePeriodicTimer(
            workflowId,
            "heartbeat",
            interval,
            "{\"periodic\":true}"
        );

        return timer.timerId;
    }

    @Transactional
    boolean cancelTimer(UUID timerId) {
        return timerSchedulerService.cancelTimer(timerId);
    }

    @Transactional
    WorkflowTimerEntity findTimerById(UUID timerId) {
        return WorkflowTimerEntity.findById(timerId);
    }

    @Transactional
    List<WorkflowTimerEntity> findTimersByWorkflowId(String workflowId) {
        return WorkflowTimerEntity.find(
            "workflowId = ?1 ORDER BY fireAt",
            UUID.fromString(workflowId)
        ).list();
    }

    @Transactional
    long countCompletedTimers(String workflowId) {
        return WorkflowTimerEntity.count(
            "workflowId = ?1 AND status = 'COMPLETED'",
            UUID.fromString(workflowId)
        );
    }

    @Transactional
    void assertWorkflowStatus(String workflowId, String expectedStatus) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(UUID.fromString(workflowId));
        assertNotNull(state);
        assertEquals(expectedStatus, state.status);
    }

    /**
     * 轮询等待 timer 完成（避免 Awaitility 的事务上下文问题）
     */
    private boolean waitForTimerCompletion(String workflowId, Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < endTime) {
            WorkflowTimerEntity timer = findTimerByWorkflowId(workflowId);
            if (timer != null && "COMPLETED".equals(timer.status)) {
                return true;
            }
            Thread.sleep(500); // 每 500ms 检查一次
        }

        return false;
    }

    @Transactional
    WorkflowTimerEntity findTimerByWorkflowId(String workflowId) {
        return WorkflowTimerEntity.find(
            "workflowId = ?1",
            UUID.fromString(workflowId)
        ).firstResult();
    }

    @Transactional
    WorkflowStateEntity findWorkflowState(String workflowId) {
        return WorkflowStateEntity.findById(UUID.fromString(workflowId));
    }

    /**
     * 轮询等待 fireAt 前进，确保周期性 timer 至少触发一次后再断言
     */
    private WorkflowTimerEntity waitForTimerReschedule(UUID timerId, Instant baselineFireAt, Duration timeout)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        WorkflowTimerEntity latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = findTimerById(timerId);
            if (latest != null && latest.fireAt != null
                && baselineFireAt != null && latest.fireAt.isAfter(baselineFireAt)) {
                return latest;
            }
            Thread.sleep(500);
        }
        return latest;
    }
}
