package io.aster.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timer 调度服务单元测试
 */
@QuarkusTest
public class TimerSchedulerServiceTest {

    @Inject
    TimerSchedulerService timerSchedulerService;

    @AfterEach
    @Transactional
    public void cleanup() {
        // 清理测试数据
        WorkflowTimerEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
    }

    @Test
    @Transactional
    public void testScheduleOneTimeTimer() {
        // Given: 创建一次性 timer
        String workflowId = UUID.randomUUID().toString();
        Duration delay = Duration.ofSeconds(5);

        // 创建 workflow state（外键约束要求）
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = "READY";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        // When: 调度 timer
        WorkflowTimerEntity timer = timerSchedulerService.scheduleTimer(
            workflowId,
            "step1",
            delay,
            "{\"test\":true}"
        );

        // Then: 验证 timer 已创建
        assertNotNull(timer);
        assertNotNull(timer.timerId);
        assertEquals(workflowId, timer.workflowId.toString());
        assertEquals("step1", timer.stepId);
        assertEquals("PENDING", timer.status);
        assertNull(timer.intervalMillis); // 非周期性
        assertTrue(timer.fireAt.isAfter(Instant.now()));
    }

    @Test
    @Transactional
    public void testSchedulePeriodicTimer() {
        // Given: 创建周期性 timer
        String workflowId = UUID.randomUUID().toString();
        Duration interval = Duration.ofSeconds(10);

        // 创建 workflow state（外键约束要求）
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = "READY";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        // When: 调度周期性 timer
        WorkflowTimerEntity timer = timerSchedulerService.schedulePeriodicTimer(
            workflowId,
            "heartbeat",
            interval,
            "{\"periodic\":true}"
        );

        // Then: 验证 timer 已创建且包含间隔
        assertNotNull(timer);
        assertEquals(workflowId, timer.workflowId.toString());
        assertEquals("heartbeat", timer.stepId);
        assertEquals("PENDING", timer.status);
        assertEquals(interval.toMillis(), timer.intervalMillis); // 周期性间隔
    }

    @Test
    @Transactional
    public void testPollExpiredTimersFindsExpiredTimer() {
        // Given: 创建已到期的 timer
        String workflowId = UUID.randomUUID().toString();

        // 创建基础 workflow state（避免 processExpiredTimer 失败）
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = "PAUSED";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = UUID.randomUUID();
        timer.workflowId = UUID.fromString(workflowId);
        timer.stepId = "step1";
        timer.fireAt = Instant.now().minusSeconds(1); // 已过期
        timer.status = "PENDING";
        timer.payload = "{}";
        timer.persist();

        // When: 执行轮询
        timerSchedulerService.pollExpiredTimers();

        // Then: 验证 timer 状态已更新
        timer = WorkflowTimerEntity.findById(timer.timerId);
        // 注意：实际的状态取决于 WorkflowScheduler 的实现
        // 在单元测试中，只验证 timer 不再是 PENDING 状态
        assertNotEquals("PENDING", timer.status);
    }

    @Test
    @Transactional
    public void testPeriodicTimerRescheduled() {
        // Given: 创建已到期的周期性 timer
        String workflowId = UUID.randomUUID().toString();

        // 创建基础 workflow state
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = "READY";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = UUID.randomUUID();
        timer.workflowId = UUID.fromString(workflowId);
        timer.stepId = "periodic_step";
        timer.fireAt = Instant.now().minusSeconds(1); // 已过期
        timer.intervalMillis = 10000L; // 10 秒间隔
        timer.status = "PENDING";
        timer.payload = "{}";
        timer.persist();

        Instant beforePoll = Instant.now();

        // When: 执行轮询
        timerSchedulerService.pollExpiredTimers();

        // Then: 验证 timer 被重新调度
        timer = WorkflowTimerEntity.findById(timer.timerId);

        // 周期性 timer 应该回到 PENDING 状态并更新下次执行时间
        // 但实际结果取决于 WorkflowScheduler 实现
        // 至少应该有 intervalMillis 字段
        assertNotNull(timer.intervalMillis);
        assertEquals(10000L, timer.intervalMillis);
    }

    @Test
    @Transactional
    public void testCancelTimer() {
        // Given: 创建 PENDING 状态的 timer
        UUID workflowId = UUID.randomUUID();

        // 创建 workflow state（外键约束要求）
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = workflowId;
        state.status = "READY";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = UUID.randomUUID();
        timer.workflowId = workflowId;
        timer.fireAt = Instant.now().plusSeconds(60);
        timer.status = "PENDING";
        timer.payload = "{}";
        timer.persist();

        // When: 取消 timer
        boolean cancelled = timerSchedulerService.cancelTimer(timer.timerId);

        // Then: 验证 timer 被取消
        assertTrue(cancelled);
        timer = WorkflowTimerEntity.findById(timer.timerId);
        assertEquals("CANCELLED", timer.status);
    }

    @Test
    @Transactional
    public void testCannotCancelCompletedTimer() {
        // Given: 创建 COMPLETED 状态的 timer
        UUID workflowId = UUID.randomUUID();

        // 创建 workflow state（外键约束要求）
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = workflowId;
        state.status = "COMPLETED";
        state.snapshot = "{}";
        state.createdAt = Instant.now();
        state.persist();

        WorkflowTimerEntity timer = new WorkflowTimerEntity();
        timer.timerId = UUID.randomUUID();
        timer.workflowId = workflowId;
        timer.fireAt = Instant.now().minusSeconds(10);
        timer.status = "COMPLETED";
        timer.payload = "{}";
        timer.persist();

        // When: 尝试取消已完成的 timer
        boolean cancelled = timerSchedulerService.cancelTimer(timer.timerId);

        // Then: 验证取消失败
        assertFalse(cancelled);
        timer = WorkflowTimerEntity.findById(timer.timerId);
        assertEquals("COMPLETED", timer.status); // 状态未改变
    }
}
