package io.aster.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQRS 查询投影测试
 *
 * 验证：
 * 1. 投影从事件流自动更新
 * 2. 预计算统计正确性（事件数、步骤数、性能指标）
 * 3. Read Model 与 Write Model 最终一致性
 * 4. 错误处理与恢复
 */
@QuarkusTest
class WorkflowQueryProjectionTest extends CrashRecoveryTestBase {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    WorkflowSchedulerService schedulerService;

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanup() {
        WorkflowQueryViewEntity.deleteAll();
        WorkflowTimerEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
    }

    /**
     * 测试：基本投影更新
     *
     * Given：创建 workflow 并追加 WorkflowStarted 事件
     * When：查询 workflow_query_view
     * Then：投影应包含基本信息（status, createdAt, startedAt, totalEvents）
     */
    @Test
    void testBasicProjectionUpdate() {
        // Given：创建 workflow 并追加 WorkflowStarted 事件
        String workflowId = bootstrapWorkflow("READY");
        appendWorkflowStarted(workflowId, Map.of("policyVersionId", 123L));

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：投影应包含基本信息
        assertThat(view.status).isEqualTo("RUNNING");
        assertThat(view.policyVersionId).isEqualTo(123L);
        assertThat(view.totalEvents).isEqualTo(1L);  // WorkflowStarted 事件
        assertThat(view.createdAt).isNotNull();
        assertThat(view.startedAt).isNotNull();
        assertThat(view.updatedAt).isNotNull();
    }

    /**
     * 测试：步骤统计
     *
     * Given：workflow 执行 10 个步骤（5 成功 + 2 失败）
     * When：查询投影
     * Then：totalSteps=7, completedSteps=5, failedSteps=2
     */
    @Test
    void testStepCounting() {
        // Given：创建 workflow 并执行步骤
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        // 5 个成功步骤
        IntStream.range(1, 6).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompleted(workflowId, "step-" + i, List.of(), "ok-" + i, false);
        });

        // 2 个失败步骤
        IntStream.range(6, 8).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepFailed(workflowId, "step-" + i, "error-" + i);
        });

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：统计正确
        assertThat(view.totalSteps).isEqualTo(7);  // 7 个 StepStarted
        assertThat(view.completedSteps).isEqualTo(5);  // 5 个 StepCompleted
        assertThat(view.failedSteps).isEqualTo(2);  // 2 个 StepFailed
        assertThat(view.totalEvents).isEqualTo(15L);  // 1 WorkflowStarted + 7 StepStarted + 5 StepCompleted + 2 StepFailed
    }

    /**
     * 测试：性能指标计算
     *
     * Given：workflow 执行完成
     * When：查询投影
     * Then：durationMs, avgStepDurationMs 应正确计算
     */
    @Test
    void testPerformanceMetrics() throws InterruptedException {
        // Given：创建 workflow 并执行
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        // 等待一小段时间，确保有可测量的执行时长
        Thread.sleep(50);

        // 执行 3 个步骤（带 duration 信息）
        IntStream.range(1, 4).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompletedWithDuration(workflowId, "step-" + i, 100L * i);  // 100ms, 200ms, 300ms
        });

        // 完成 workflow
        appendWorkflowCompleted(workflowId, Map.of("result", "success"));

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：性能指标正确
        assertThat(view.durationMs).isNotNull();
        assertThat(view.durationMs).isGreaterThan(0L);  // 应该有执行时长
        assertThat(view.avgStepDurationMs).isEqualTo(200L);  // (100 + 200 + 300) / 3 = 200
        assertThat(view.completedAt).isNotNull();
        assertThat(view.status).isEqualTo("COMPLETED");
    }

    /**
     * 测试：错误信息捕获
     *
     * Given：workflow 执行失败
     * When：查询投影
     * Then：errorMessage 应包含错误信息
     */
    @Test
    void testErrorCapture() {
        // Given：创建 workflow 并失败
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        appendStepStarted(workflowId, "step-1", List.of());
        appendStepFailed(workflowId, "step-1", "Database connection timeout");

        appendWorkflowFailed(workflowId, "Workflow failed due to step-1 error");

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：错误信息捕获
        assertThat(view.status).isEqualTo("FAILED");
        assertThat(view.errorMessage).contains("Workflow failed");
        assertThat(view.failedSteps).isEqualTo(1);
        assertThat(view.completedAt).isNotNull();
    }

    /**
     * 测试：Compensation 场景统计
     *
     * Given：workflow 触发补偿
     * When：查询投影
     * Then：状态应正确反映补偿流程
     */
    @Test
    void testCompensationStatistics() {
        // Given：创建 workflow 并执行补偿
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        // 执行 3 个步骤（带补偿）
        IntStream.range(1, 4).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompleted(workflowId, "step-" + i, List.of(), "ok-" + i, true);  // hasCompensation=true
        });

        // 触发补偿
        appendCompensationScheduled(workflowId, List.of("step-3", "step-2", "step-1"));
        appendCompensationCompleted(workflowId);

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：状态正确
        assertThat(view.status).isEqualTo("COMPENSATED");
        assertThat(view.completedSteps).isEqualTo(3);
        assertThat(view.totalSteps).isEqualTo(3);
    }

    /**
     * 测试：Snapshot 引用更新
     *
     * Given：workflow 有 snapshot
     * When：查询投影
     * Then：hasSnapshot=true, snapshotSeq 正确
     */
    @Test
    void testSnapshotReferenceUpdate() {
        // Given：创建 workflow 并追加 100 个事件（触发 snapshot at 100）
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        IntStream.range(1, 51).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompleted(workflowId, "step-" + i, List.of(), "ok-" + i, false);
        });

        // When：查询投影
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：snapshot 引用正确
        assertThat(view.hasSnapshot).isTrue();
        assertThat(view.snapshotSeq).isEqualTo(100L);  // snapshot at seq 100
        assertThat(view.totalEvents).isEqualTo(101L);  // 1 WorkflowStarted + 50*2 = 101
    }

    /**
     * 测试：投影与 Write Model 一致性
     *
     * Given：workflow 执行完整流程
     * When：比较 workflow_state 和 workflow_query_view
     * Then：关键字段应一致（status, totalEvents, completedAt）
     */
    @Test
    void testProjectionConsistency() {
        // Given：创建并执行 workflow
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        IntStream.range(1, 6).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompleted(workflowId, "step-" + i, List.of(), "ok-" + i, false);
        });

        appendWorkflowCompleted(workflowId, Map.of("result", "final-result"));

        // When：查询 Write Model 和 Read Model
        WorkflowStateEntity state = findWorkflowState(workflowId);
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();

        // Then：关键字段一致
        assertThat(view.status).isEqualTo(state.status);  // COMPLETED
        assertThat(view.totalEvents).isEqualTo(state.lastEventSeq);  // 12 events
        assertThat(view.completedAt).isNotNull();
        assertThat(state.completedAt).isNotNull();
        assertThat(view.policyVersionId).isEqualTo(state.policyVersionId);
    }

    /**
     * 测试：大量事件场景下的投影性能
     *
     * Given：workflow 有 500+ 事件
     * When：查询投影
     * Then：投影应正确且查询快速
     */
    @Test
    void testLargeScaleProjection() {
        // Given：创建 workflow 并追加 500 个事件（250 loops × 2）
        String workflowId = bootstrapWorkflow("RUNNING");
        appendWorkflowStarted(workflowId, Map.of());

        Instant startAppend = Instant.now();
        IntStream.range(1, 251).forEach(i -> {
            appendStepStarted(workflowId, "step-" + i, List.of());
            appendStepCompleted(workflowId, "step-" + i, List.of(), "ok-" + i, false);
        });
        long appendDuration = java.time.Duration.between(startAppend, Instant.now()).toMillis();

        appendWorkflowCompleted(workflowId, Map.of("result", "large-scale-test"));

        // When：查询投影（应该很快，因为是预计算的）
        Instant startQuery = Instant.now();
        WorkflowQueryViewEntity view = WorkflowQueryViewEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow();
        long queryDuration = java.time.Duration.between(startQuery, Instant.now()).toMillis();

        // Then：投影正确且查询快速
        assertThat(view.totalEvents).isEqualTo(502L);  // 1 WorkflowStarted + 250*2 + 1 WorkflowCompleted
        assertThat(view.totalSteps).isEqualTo(250);
        assertThat(view.completedSteps).isEqualTo(250);
        assertThat(view.status).isEqualTo("COMPLETED");

        // 查询应远快于事件追加（预计算的优势）
        assertThat(queryDuration).isLessThan(appendDuration / 10);
    }

    // ========================= 辅助方法 =========================

    @Transactional
    String bootstrapWorkflow(String status) {
        String workflowId = UUID.randomUUID().toString();
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.fromString(workflowId);
        state.status = status;
        state.snapshot = "{}";
        state.lastEventSeq = 0L;
        state.snapshotSeq = 0L;
        state.createdAt = Instant.now();
        state.updatedAt = state.createdAt;
        state.persist();
        return workflowId;
    }

    private void appendWorkflowStarted(String workflowId, Map<String, ?> payload) {
        eventStore.appendEvent(workflowId, "WorkflowStarted", payload);
        markWorkflowStarted(workflowId);
    }

    private void appendStepStarted(String workflowId, String stepId, List<String> dependencies) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", new ArrayList<>(dependencies));
        payload.put("status", "STARTED");
        payload.put("startedAt", Instant.now().toString());
        eventStore.appendEvent(workflowId, "StepStarted", payload);
    }

    private void appendStepCompleted(String workflowId,
                                     String stepId,
                                     List<String> dependencies,
                                     Object result,
                                     boolean hasCompensation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", new ArrayList<>(dependencies));
        payload.put("status", "COMPLETED");
        payload.put("completedAt", Instant.now().toString());
        payload.put("hasCompensation", hasCompensation);
        if (result != null) {
            payload.put("result", result);
        }
        eventStore.appendEvent(workflowId, "StepCompleted", payload);
    }

    private void appendStepCompletedWithDuration(String workflowId, String stepId, long durationMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", stepId);
        payload.put("dependencies", List.of());
        payload.put("status", "COMPLETED");
        payload.put("completedAt", Instant.now().toString());
        payload.put("hasCompensation", false);
        payload.put("duration", durationMs);
        eventStore.appendEvent(workflowId, "StepCompleted", payload);
    }

    private void appendStepFailed(String workflowId, String stepId, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepId", stepId);
        payload.put("error", error);
        payload.put("failedAt", Instant.now().toString());
        eventStore.appendEvent(workflowId, "StepFailed", payload);
    }

    private void appendWorkflowCompleted(String workflowId, Map<String, ?> result) {
        eventStore.appendEvent(workflowId, "WorkflowCompleted", result);
        markWorkflowCompleted(workflowId, "COMPLETED");
    }

    private void appendWorkflowFailed(String workflowId, String error) {
        Map<String, Object> payload = Map.of("error", error);
        eventStore.appendEvent(workflowId, "WorkflowFailed", payload);
        markWorkflowCompleted(workflowId, "FAILED");
    }

    private void appendCompensationScheduled(String workflowId, List<String> steps) {
        Map<String, Object> payload = Map.of("steps", steps);
        eventStore.appendEvent(workflowId, "CompensationScheduled", payload);
    }

    private void appendCompensationCompleted(String workflowId) {
        eventStore.appendEvent(workflowId, "CompensationCompleted", Map.of());
    }

    @Transactional
    void markWorkflowStarted(String workflowId) {
        WorkflowStateEntity state = findWorkflowState(workflowId);
        state.markStarted();
        state.persist();
    }

    @Transactional
    void markWorkflowCompleted(String workflowId, String finalStatus) {
        WorkflowStateEntity state = findWorkflowState(workflowId);
        state.markCompleted(finalStatus);
        state.persist();
    }
}
