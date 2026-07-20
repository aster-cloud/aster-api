package io.aster.api.workflow;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Timer 崩溃恢复测试，覆盖执行中/失败/周期性 timer 的恢复路径。
 */
@QuarkusTest
@TestProfile(CrashRecoveryTestProfile.class)
public class TimerCrashRecoveryIT extends CrashRecoveryTestBase {

    private static final Duration SHORT_DELAY = Duration.ofSeconds(1);
    // 远期延迟：让 timer 创建后长期不到期，避免在「桩安装前」出现 PENDING+到期 的可被拾取窗口。
    private static final Duration FAR_FUTURE_DELAY = Duration.ofSeconds(60);
    private static final Duration PERIODIC_INTERVAL = Duration.ofSeconds(2);

    @InjectSpy
    WorkflowSchedulerService workflowScheduler;

    // 抛错桩的「数据面」：桩只在首个 @BeforeEach 注册一次并永久保留，每个测试仅翻转这些原子变量即可控制行为。
    //
    // ★实测根因（flaky 的真正来源）：@Scheduled(1s) 后台轮询线程持续运行，而 Mockito 的 reset(spy)
    // 会瞬时把 spy 复位为「默认调真实方法」。任何一次 reset→重新注册桩 的间隙里，若轮询 tick 命中一个
    // 到期的 PENDING timer，就会绕过桩直接跑真实 resumeWorkflowStep（answer invoked=0），把 timer 跑成
    // COMPLETED retry=1，偷走测试意图。因此这里**永不 reset**：桩一次注册、永久存在，默认分支 callRealMethod()
    // 与「未打桩的 spy」行为完全一致，不影响 executing/periodic 两个测试；测试隔离改由数据面（下列原子变量）承担。
    private final AtomicBoolean stubRegistered = new AtomicBoolean(false);
    private final AtomicReference<String> throwTargetWorkflowId = new AtomicReference<>(null);
    private final AtomicBoolean throwArmFlag = new AtomicBoolean(false);
    // 证据计数器：后台线程的 System.out 不进 per-test XML，用原子计数从主线程回读，证明抛错真的落地。
    private final AtomicInteger answerInvoked = new AtomicInteger();
    private final AtomicInteger answerMatched = new AtomicInteger();
    private final AtomicInteger answerThrown = new AtomicInteger();
    private final AtomicReference<String> lastInvokedWorkflowId = new AtomicReference<>("<none>");

    @BeforeEach
    @AfterEach
    @Transactional
    public void cleanup() {
        WorkflowTimerEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
    }

    @BeforeEach
    public void setUpResumeStub() {
        // 每个测试仅复位数据面与计数器（不触碰桩本身）。
        throwTargetWorkflowId.set(null);
        throwArmFlag.set(false);
        answerInvoked.set(0);
        answerMatched.set(0);
        answerThrown.set(0);
        lastInvokedWorkflowId.set("<none>");

        // 桩只注册一次并永久保留（不 reset）。默认 callRealMethod() 保留真实行为，
        // 仅当调用参数 workflowId 等于当前测试武装的目标、且 armFlag 处于 armed 时抛错一次
        //（compareAndSet 保证只抛一次）。事实依据：TimerSchedulerService:64 传入
        // timer.workflowId(UUID).toString()，与 createWorkflowWithEvents 生成的 workflowId(String) 字符串相等。
        if (!stubRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            // 同时给 resumeWorkflowStep(2 参) 与 resumeWorkflow(1 参) 打同一套抛错逻辑：
            // processExpiredTimer 依 timer.stepId 是否为 null 选择其一，覆盖两条路径确保抛错必落地。
            doAnswer(invocation -> resumeAnswer("resumeWorkflowStep", invocation))
                .when(workflowScheduler).resumeWorkflowStep(anyString(), anyString());
            doAnswer(invocation -> resumeAnswer("resumeWorkflow", invocation))
                .when(workflowScheduler).resumeWorkflow(anyString());
        } catch (Throwable e) {
            throw new IllegalStateException("注册 resume 桩失败", e);
        }
    }

    /**
     * resume 系列方法的共享桩逻辑：默认走真实方法，仅当 workflowId 命中武装目标时抛错一次。
     */
    private Object resumeAnswer(String method, org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        String invokedWorkflowId = invocation.getArgument(0);
        answerInvoked.incrementAndGet();
        lastInvokedWorkflowId.set(method + ":" + invokedWorkflowId);
        String target = throwTargetWorkflowId.get();
        boolean match = target != null && target.equals(invokedWorkflowId);
        if (match) {
            answerMatched.incrementAndGet();
        }
        if (match && throwArmFlag.compareAndSet(true, false)) {
            answerThrown.incrementAndGet();
            throw new RuntimeException("模拟 step 执行失败");
        }
        return invocation.callRealMethod();
    }

    @Test
    public void testExecutingTimerRecovery() {
        TimerContext context = createOneTimeTimer(SHORT_DELAY);
        makeTimerDue(context.timerId());
        setTimerStatus(context.timerId(), "EXECUTING");

        // 模拟执行中崩溃 -> 回退为 PENDING，等待调度重新拾取。
        simulateTimerCrash(context.timerId());

        // 后台调度器已关闭：同步驱动一次 pollExpiredTimers()，确定性地拾取 PENDING+到期 timer 并完成它。
        drivePollOnce();
        WorkflowTimerEntity timer = findTimerByIdFresh(context.timerId());
        logTimerDiagnostics("executing-recovery", context.timerId());

        assertThat(timer).as("Timer 应在崩溃恢复后完成").isNotNull();
        assertThat(timer.status).isEqualTo("COMPLETED");
    }

    @Test
    public void testFailedTimerRetry() {
        // ★关键顺序：先把 timer 置为非 PENDING（FAILED）再 makeTimerDue，最后才通过 simulateTimerCrash
        // 转回 PENDING。这样在「桩武装」之前，timer 永远不会同时处于 PENDING+到期 状态，避免轮询线程
        // 在武装前抢先拾取。用 FAR_FUTURE_DELAY 创建，保证 create→setFAILED 之间不会自动到期。
        TimerContext context = createOneTimeTimer(FAR_FUTURE_DELAY);
        setTimerStatus(context.timerId(), "FAILED");
        setTimerRetryCount(context.timerId(), 1);
        makeTimerDue(context.timerId());

        // 桩已在 @BeforeEach 稳定注册，此处仅翻转「数据面」：设定抛错目标 workflowId 并武装。
        // 事实依据：TimerSchedulerService:64 传入 timer.workflowId(UUID).toString()，
        // 与 createWorkflowWithEvents 生成的 workflowId(String) 字符串相等，故按字符串匹配可靠。
        String targetWorkflowId = context.workflowId();
        throwTargetWorkflowId.set(targetWorkflowId);
        throwArmFlag.set(true);

        // 触发崩溃（FAILED -> PENDING+到期）。
        simulateTimerCrash(context.timerId());

        // ★同步驱动重试（后台调度器已关闭，无并发竞态）。
        // 之所以必须同步驱动：@Scheduled(1s) 后台轮询线程调用 @InjectSpy 的 workflowScheduler 时，
        // Mockito spy 在并发后台线程下约 50% 概率被绕过（answer invoked=0），直接跑真实 resumeWorkflowStep
        // 把 timer 完成成 COMPLETED retry=1，抛错永不落地。现由测试类关闭后台调度器 + 测试线程同步调用
        // 公开的 pollExpiredTimers()：同一注入引用在测试线程上调用 spy 是确定命中的。
        // 第一次同步驱动即拾取本 timer 并命中已武装的抛错桩 -> catch -> FAILED retry=2。
        drivePollOnce();
        WorkflowTimerEntity failedSnapshot = findTimerByIdFresh(context.timerId());
        System.out.printf(
            "[TimerDiag][answer-evidence] invoked=%d matched=%d thrown=%d lastInvokedWf=%s target=%s%n",
            answerInvoked.get(), answerMatched.get(), answerThrown.get(), lastInvokedWorkflowId.get(), targetWorkflowId
        );
        logTimerDiagnostics("failed-first-retry", context.timerId());

        assertThat(answerThrown.get()).as("桩应真实抛错恰好一次").isEqualTo(1);
        assertThat(failedSnapshot).isNotNull();
        assertThat(failedSnapshot.retryCount).as("抛错后 retryCount 应从初始值 1 递增到 >=2").isGreaterThan(1);
        assertThat(failedSnapshot.status).as("抛错后 timer 状态应回到 FAILED").isEqualTo("FAILED");

        // 第二次重试不再抛错（armFlag 已消费为 false）：再次同步驱动使其成功完成。
        simulateTimerCrash(context.timerId());

        drivePollOnce();
        WorkflowTimerEntity completedSnapshot = findTimerByIdFresh(context.timerId());
        logTimerDiagnostics("failed-second-retry", context.timerId());

        assertThat(completedSnapshot).isNotNull();
        assertThat(completedSnapshot.status).as("Timer 应在第二次重试后完成").isEqualTo("COMPLETED");
        assertThat(completedSnapshot.retryCount)
            .as("成功完成后不再变更 retryCount")
            .isEqualTo(failedSnapshot.retryCount);
    }

    /**
     * 同步驱动一次 timer 调度：在测试线程上直接调用公开的 pollExpiredTimers()，确定性地处理所有
     * PENDING+到期 timer。后台调度器已在本测试类关闭，此处不存在并发竞态，一次调用即完成状态转换。
     */
    private void drivePollOnce() {
        timerSchedulerService.pollExpiredTimers();
    }

    @Test
    public void testPeriodicTimerCrashRecovery() {
        TimerContext context = createPeriodicTimer(PERIODIC_INTERVAL);
        makeTimerDue(context.timerId());
        Instant initialFireAt = getTimerFireAt(context.timerId());

        // 首次同步驱动：周期性 timer 触发后应重排 fireAt 到未来并回到 PENDING。
        drivePollOnce();
        Instant firstRescheduled = getTimerFireAt(context.timerId());
        assertThat(firstRescheduled)
            .as("周期性 timer 首次触发后 fireAt 应向前推进")
            .isAfter(initialFireAt);

        // 模拟执行中崩溃 -> 回退为 PENDING，并再次置为到期以便下一次驱动拾取。
        setTimerStatus(context.timerId(), "EXECUTING");
        simulateTimerCrash(context.timerId());
        makeTimerDue(context.timerId());

        // 再次同步驱动：崩溃恢复后应再次重排 fireAt 并回到 PENDING。
        drivePollOnce();
        WorkflowTimerEntity timer = findTimerByIdFresh(context.timerId());
        Instant recoveredFireAt = timer != null ? timer.fireAt : null;
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
