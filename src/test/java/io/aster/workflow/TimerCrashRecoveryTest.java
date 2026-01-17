package io.aster.workflow;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Timer 崩溃恢复测试，覆盖执行中/失败/周期性 timer 的恢复路径。
 */
@QuarkusTest
@TestProfile(CrashRecoveryTestProfile.class)
public class TimerCrashRecoveryTest extends CrashRecoveryTestBase {

    private static final Duration SHORT_DELAY = Duration.ofSeconds(1);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PERIODIC_INTERVAL = Duration.ofSeconds(2);

    @InjectSpy
    WorkflowSchedulerService workflowScheduler;

    @BeforeEach
    @AfterEach
    @Transactional
    public void cleanup() {
        WorkflowTimerEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
    }

    @BeforeEach
    public void resetWorkflowSchedulerMock() {
        reset(workflowScheduler);
    }

    @Test
    public void testExecutingTimerRecovery() {
        TimerContext context = createOneTimeTimer(SHORT_DELAY);
        makeTimerDue(context.timerId());
        setTimerStatus(context.timerId(), "EXECUTING");

        // 模拟执行中崩溃 -> 回退为 PENDING 等待调度线程重新拾取
        simulateTimerCrash(context.timerId());

        boolean completed = waitForTimerStatus(context.timerId(), "COMPLETED", WAIT_TIMEOUT);
        logTimerDiagnostics("executing-recovery", context.timerId());

        assertThat(completed).as("Timer 应在崩溃恢复后完成").isTrue();
        WorkflowTimerEntity timer = findTimerById(context.timerId());
        assertThat(timer).isNotNull();
        assertThat(timer.status).isEqualTo("COMPLETED");
    }

    @Test
    public void testFailedTimerRetry() {
        TimerContext context = createOneTimeTimer(SHORT_DELAY);
        makeTimerDue(context.timerId());
        setTimerStatus(context.timerId(), "FAILED");
        setTimerRetryCount(context.timerId(), 1);

        // 第一次重试强制抛错 -> 验证 retryCount 递增
        doThrow(new RuntimeException("模拟 step 执行失败"))
            .doNothing()
            .when(workflowScheduler)
            .resumeWorkflowStep(anyString(), anyString());

        simulateTimerCrash(context.timerId());

        boolean failedAgain = waitForTimerStatus(context.timerId(), "FAILED", WAIT_TIMEOUT);
        WorkflowTimerEntity failedSnapshot = findTimerById(context.timerId());
        logTimerDiagnostics("failed-first-retry", context.timerId());

        assertThat(failedAgain).as("第一次重试应再次失败以触发 retryCount 递增").isTrue();
        assertThat(failedSnapshot).isNotNull();
        assertThat(failedSnapshot.retryCount).isGreaterThan(1);

        // 第二次重试改为正常执行，timer 应最终完成
        simulateTimerCrash(context.timerId());

        boolean completed = waitForTimerStatus(context.timerId(), "COMPLETED", WAIT_TIMEOUT);
        WorkflowTimerEntity completedSnapshot = findTimerById(context.timerId());
        logTimerDiagnostics("failed-second-retry", context.timerId());

        assertThat(completed).as("Timer 应在第二次重试后完成").isTrue();
        assertThat(completedSnapshot).isNotNull();
        assertThat(completedSnapshot.status).isEqualTo("COMPLETED");
        assertThat(completedSnapshot.retryCount)
            .as("成功完成后不再变更 retryCount")
            .isEqualTo(failedSnapshot.retryCount);
    }

    @Test
    public void testPeriodicTimerCrashRecovery() throws InterruptedException {
        TimerContext context = createPeriodicTimer(PERIODIC_INTERVAL);
        Instant initialFireAt = getTimerFireAt(context.timerId());

        Instant firstRescheduled = waitForFireAtAdvance(context.timerId(), initialFireAt, Duration.ofSeconds(8));
        assertThat(firstRescheduled)
            .as("周期性 timer 首次触发后 fireAt 应向前推进")
            .isAfter(initialFireAt);

        setTimerStatus(context.timerId(), "EXECUTING");
        simulateTimerCrash(context.timerId());

        Instant recoveredFireAt = waitForFireAtAdvance(context.timerId(), firstRescheduled, Duration.ofSeconds(8));
        WorkflowTimerEntity timer = findTimerById(context.timerId());
        logTimerDiagnostics("periodic-recovery", context.timerId());

        assertThat(timer).isNotNull();
        assertThat(timer.status)
            .as("周期性 timer 恢复后仍应回到 PENDING 以便下一次轮询")
            .isEqualTo("PENDING");
        assertThat(timer.intervalMillis).isEqualTo(PERIODIC_INTERVAL.toMillis());
        assertThat(recoveredFireAt)
            .as("发生崩溃后再次调度 fireAt 应再次向未来推进")
            .isAfter(firstRescheduled);
    }

    /**
     * 创建一次性 timer 并返回上下文。
     */
    private TimerContext createOneTimeTimer(Duration delay) {
        String workflowId = createWorkflowWithEvents("PAUSED");
        UUID timerId = createTimerWithWorkflow(workflowId, delay);
        return new TimerContext(workflowId, timerId);
    }

    /**
     * 创建周期性 timer 并返回上下文。
     */
    private TimerContext createPeriodicTimer(Duration interval) {
        String workflowId = createWorkflowWithEvents("READY");
        UUID timerId = createPeriodicTimerWithWorkflow(workflowId, interval);
        return new TimerContext(workflowId, timerId);
    }

    /**
     * 诊断输出：打印 timer 当前状态，便于追踪失败原因。
     */
    private void logTimerDiagnostics(String label, UUID timerId) {
        WorkflowTimerEntity timer = findTimerById(timerId);
        if (timer == null) {
            System.out.printf("[TimerDiag][%s] timer %s 不存在%n", label, timerId);
            return;
        }
        System.out.printf(
            "[TimerDiag][%s] timer=%s status=%s retry=%d fireAt=%s interval=%s%n",
            label,
            timerId,
            timer.status,
            timer.retryCount,
            timer.fireAt,
            timer.intervalMillis
        );
    }

    /**
     * 轮询直到 fireAt 向未来推进，用于验证周期性 timer 重新调度。
     */
    private Instant waitForFireAtAdvance(UUID timerId, Instant baseline, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Instant latest = baseline;
        while (System.currentTimeMillis() < deadline) {
            Instant current = getTimerFireAt(timerId);
            if (current != null && baseline != null && current.isAfter(baseline)) {
                return current;
            }
            Thread.sleep(500);
            latest = current;
        }
        return latest;
    }

    @Transactional
    UUID createTimerWithWorkflow(String workflowId, Duration delay) {
        ensureWorkflowStateExists(workflowId);
        WorkflowTimerEntity timer = timerSchedulerService.scheduleTimer(
            workflowId,
            "step-" + UUID.randomUUID(),
            delay,
            "{\"source\":\"timer-crash-test\"}"
        );
        return timer.timerId;
    }

    @Transactional
    UUID createPeriodicTimerWithWorkflow(String workflowId, Duration interval) {
        ensureWorkflowStateExists(workflowId);
        WorkflowTimerEntity timer = timerSchedulerService.schedulePeriodicTimer(
            workflowId,
            "heartbeat",
            interval,
            "{\"source\":\"timer-crash-test\",\"periodic\":true}"
        );
        return timer.timerId;
    }

    @Transactional
    void setTimerStatus(UUID timerId, String status) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer != null) {
            timer.status = status;
            timer.persist();
        }
    }

    @Transactional
    void setTimerRetryCount(UUID timerId, int retryCount) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer != null) {
            timer.retryCount = retryCount;
            timer.persist();
        }
    }

    @Transactional
    void makeTimerDue(UUID timerId) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        if (timer != null) {
            timer.fireAt = Instant.now().minusSeconds(1);
            timer.persist();
        }
    }

    @Transactional
    Instant getTimerFireAt(UUID timerId) {
        WorkflowTimerEntity timer = WorkflowTimerEntity.findById(timerId);
        return timer != null ? timer.fireAt : null;
    }

    private void ensureWorkflowStateExists(String workflowId) {
        UUID wfId = UUID.fromString(workflowId);
        WorkflowStateEntity existing = WorkflowStateEntity.findById(wfId);
        if (existing == null) {
            WorkflowStateEntity state = new WorkflowStateEntity();
            state.workflowId = wfId;
            state.status = "READY";
            state.snapshot = "{}";
            state.createdAt = Instant.now();
            state.persist();
        }
    }

    private record TimerContext(String workflowId, UUID timerId) {
    }
}
