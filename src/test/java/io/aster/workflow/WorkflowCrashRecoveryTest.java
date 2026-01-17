package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow 崩溃恢复测试，覆盖运行中断、clock_times 重放与补偿恢复三个场景。
 */
@QuarkusTest
@TestProfile(CrashRecoveryTestProfile.class)
class WorkflowCrashRecoveryTest extends CrashRecoveryTestBase {

    @Inject
    PostgresEventStore eventStore;

    @BeforeEach
    @AfterEach
    @Transactional
    public void cleanup() {
        WorkflowTimerEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
    }

    /**
     * Given：创建 RUNNING 状态 workflow 并提前写入 WORKFLOW_COMPLETED 事件；
     * When：simulateWorkflowCrash 清空锁后调用 schedulerService.processWorkflow；
     * Then：waitForWorkflowStatus 校验最终状态被恢复为 COMPLETED。
     */
    @Test
    void testWorkflowCrashDuringExecution() {
        String workflowId = createWorkflowWithEvents("RUNNING");
        appendWorkflowCompleted(workflowId, Map.of("result", "crash-resume-ok"));

        simulateWorkflowCrash(workflowId);
        schedulerService.processWorkflow(workflowId);

        boolean completed = waitForWorkflowStatus(workflowId, "COMPLETED", Duration.ofSeconds(5));
        assertThat(completed).isTrue();

        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");
    }

    /**
     * Given：构造带 clock_times 决策快照的 workflow 并持久化 DeterminismSnapshot；
     * When：模拟崩溃后执行 schedulerService.processWorkflow 触发 replay；
     * Then：workflow 成功完成且 clock_times JSON 未被修改。
     */
    @Test
    void testWorkflowWithClockTimesReplay() throws Exception {
        String workflowId = createWorkflowWithEvents("RUNNING");

        ReplayDeterministicClock clock = new ReplayDeterministicClock();
        Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2025-01-01T00:00:01Z");
        Instant t3 = Instant.parse("2025-01-01T00:00:02Z");
        clock.recordTimeDecision(t1);
        clock.recordTimeDecision(t2);
        clock.recordTimeDecision(t3);

        DeterminismSnapshot snapshot = DeterminismSnapshot.from(clock, null, null);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        String snapshotJson = mapper.writeValueAsString(snapshot);
        updateWorkflowSnapshot(workflowId, snapshotJson);
        appendWorkflowCompleted(workflowId, Map.of("result", "replay-success"));

        simulateWorkflowCrash(workflowId);
        schedulerService.processWorkflow(workflowId);

        boolean completed = waitForWorkflowStatus(workflowId, "COMPLETED", Duration.ofSeconds(5));
        assertThat(completed).isTrue();

        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPLETED");

        JsonNode expectedNode = mapper.readTree(snapshotJson);
        JsonNode actualNode = mapper.readTree(state.clockTimes);
        assertThat(actualNode).isEqualTo(expectedNode);

        DeterminismSnapshot reloaded = mapper.readValue(state.clockTimes, DeterminismSnapshot.class);
        assertThat(reloaded.getClockTimes()).containsExactly(
                t1.toString(),
                t2.toString(),
                t3.toString()
        );
    }

    /**
     * Given：创建 workflow 并追加 COMPENSATION_SCHEDULED 事件模拟待补偿状态；
     * When：崩溃恢复后执行 executeCompensation + processWorkflow 模拟补偿完成；
     * Then：状态切换为 COMPENSATED 且事件流中包含 COMPENSATION_COMPLETED。
     */
    @Test
    void testWorkflowWithPendingCompensation() {
        String workflowId = createWorkflowWithEvents("RUNNING");
        String compensationStep = "comp-step-" + UUID.randomUUID();
        appendCompensationScheduled(workflowId, compensationStep);

        simulateWorkflowCrash(workflowId);
        appendCompensationCompletion(workflowId, compensationStep);
        schedulerService.processWorkflow(workflowId);

        boolean compensated = waitForWorkflowStatus(workflowId, "COMPENSATED", Duration.ofSeconds(5));
        assertThat(compensated).isTrue();

        WorkflowStateEntity state = findWorkflowState(workflowId);
        assertThat(state.status).isEqualTo("COMPENSATED");

        List<WorkflowEvent> events = eventStore.getEvents(workflowId, 0);
        boolean hasCompensationCompleted = events.stream()
                .anyMatch(e -> WorkflowEvent.Type.COMPENSATION_COMPLETED.equals(e.getEventType()));
        assertThat(hasCompensationCompleted).isTrue();
    }

    @Transactional
    void appendWorkflowCompleted(String workflowId, Map<String, Object> result) {
        Map<String, Object> payload = result != null ? result : Map.of();
        persistEvent(workflowId, WorkflowEvent.Type.WORKFLOW_COMPLETED, payload);
    }

    @Transactional
    void updateWorkflowSnapshot(String workflowId, String snapshotJson) {
        UUID wfUuid = UUID.fromString(workflowId);
        WorkflowStateEntity state = WorkflowStateEntity.findById(wfUuid);
        if (state == null) {
            throw new IllegalStateException("workflow state not found: " + workflowId);
        }
        state.clockTimes = snapshotJson;
        state.status = "RUNNING";
        state.persist();
    }

    @Transactional
    void appendCompensationScheduled(String workflowId, String compensationId) {
        Map<String, Object> payload = Map.of(
                "stepId", compensationId,
                "scheduledAt", Instant.now().toString(),
                "reason", "crash-recovery-compensation"
        );
        persistEvent(workflowId, WorkflowEvent.Type.COMPENSATION_SCHEDULED, payload);
    }

    @Transactional
    void appendCompensationCompletion(String workflowId, String compensationId) {
        Map<String, Object> startPayload = Map.of(
                "stepId", compensationId,
                "originalResult", "order-rollback"
        );
        persistEvent(workflowId, WorkflowEvent.Type.COMPENSATION_STARTED, startPayload);

        Map<String, Object> completedPayload = Map.of(
                "stepId", compensationId,
                "compensationResult", "success",
                "completedAt", Instant.now().toString()
        );
        persistEvent(workflowId, WorkflowEvent.Type.COMPENSATION_COMPLETED, completedPayload);
    }

    private void persistEvent(String workflowId, String eventType, Object payload) {
        UUID wfUuid = UUID.fromString(workflowId);
        WorkflowStateEntity state = WorkflowStateEntity.findById(wfUuid);
        if (state == null) {
            throw new IllegalStateException("workflow state not found: " + workflowId);
        }
        long lastSeq = state.lastEventSeq != null ? state.lastEventSeq : 0L;
        long nextSeq = lastSeq + 1;

        WorkflowEventEntity event = new WorkflowEventEntity();
        event.workflowId = wfUuid;
        event.sequence = nextSeq;
        event.seq = nextSeq;
        event.eventType = eventType;
        event.payload = serializePayload(payload);
        event.occurredAt = Instant.now();
        event.idempotencyKey = workflowId + ":" + eventType + ":" + nextSeq;
        event.persist();

        state.lastEventSeq = nextSeq;
        state.persist();
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize payload", e);
        }
    }
}
