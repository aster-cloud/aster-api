package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DeterminismIntegrationTest {

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    WorkflowSchedulerService schedulerService;

    @Inject
    PostgresEventStore eventStore;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    @Transactional
    void cleanTables() {
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
        WorkflowTimerEntity.deleteAll();
    }

    @Test
    @Transactional
    void testWorkflowRecordAndReplay() throws Exception {
        String workflowId = UUID.randomUUID().toString();
        prepareWorkflowState(workflowId, "RUNNING");

        DeterminismContext recording = new DeterminismContext();
        workflowRuntime.setCurrentWorkflowId(workflowId);
        workflowRuntime.setDeterminismContext(recording);
        try {
            Instant t1 = Instant.parse("2025-01-10T08:00:00Z");
            Instant t2 = Instant.parse("2025-01-10T08:00:01Z");
            recording.clock().recordTimeDecision(t1);
            recording.clock().recordTimeDecision(t2);
            UUID uuidDecision = recording.uuid().randomUUID();
            long randomDecision = recording.random().nextLong("alpha-source");

            workflowRuntime.completeWorkflow(workflowId, Map.of("result", "ok"));

            WorkflowStateEntity persisted = reloadState(workflowId);
            assertThat(persisted.clockTimes).isNotBlank();

            DeterminismSnapshot snapshot = mapper.readValue(persisted.clockTimes, DeterminismSnapshot.class);
            assertThat(snapshot.getClockTimes()).containsExactly(t1.toString(), t2.toString());
            assertThat(snapshot.getUuids()).containsExactly(uuidDecision.toString());
            assertThat(snapshot.getRandoms()).containsEntry("alpha-source", List.of(randomDecision));

            DeterminismContext replay = new DeterminismContext();
            snapshot.applyTo(replay.clock(), replay.uuid(), replay.random());
            assertThat(replay.clock().now()).isEqualTo(t1);
            assertThat(replay.clock().now()).isEqualTo(t2);
            assertThat(replay.uuid().randomUUID()).isEqualTo(uuidDecision);
            assertThat(replay.random().nextLong("alpha-source")).isEqualTo(randomDecision);
        } finally {
            workflowRuntime.clearDeterminismContext();
            workflowRuntime.clearCurrentWorkflowId();
        }
    }

    @Test
    void testMultipleWorkflowsIsolation() throws InterruptedException {
        List<String> workflowIds = List.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        );
        Map<String, List<String>> recordedClocks = new ConcurrentHashMap<>();
        Map<String, UUID> recordedUuids = new ConcurrentHashMap<>();
        Map<String, Long> recordedRandoms = new ConcurrentHashMap<>();
        Map<String, List<String>> snapshotUuids = new ConcurrentHashMap<>();
        Map<String, List<Long>> snapshotRandoms = new ConcurrentHashMap<>();
        Map<String, Instant> baseTimes = Map.of(
                workflowIds.get(0), Instant.parse("2025-02-01T00:00:00Z"),
                workflowIds.get(1), Instant.parse("2025-02-01T00:00:10Z"),
                workflowIds.get(2), Instant.parse("2025-02-01T00:00:20Z")
        );

        ExecutorService executor = Executors.newFixedThreadPool(workflowIds.size());
        CountDownLatch latch = new CountDownLatch(workflowIds.size());

        for (String workflowId : workflowIds) {
            executor.submit(() -> {
                workflowRuntime.setCurrentWorkflowId(workflowId);
                DeterminismContext context = new DeterminismContext();
                workflowRuntime.setDeterminismContext(context);
                try {
                    Instant base = baseTimes.get(workflowId);
                    context.clock().recordTimeDecision(base);
                    context.clock().recordTimeDecision(base.plusSeconds(2));
                    UUID uuidDecision = context.uuid().randomUUID();
                    long randomDecision = context.random().nextLong("iso-" + workflowId);

                    DeterminismSnapshot snapshot = DeterminismSnapshot.from(
                            context.clock(),
                            context.uuid(),
                            context.random()
                    );
                    recordedClocks.put(workflowId, snapshot.getClockTimes());
                    recordedUuids.put(workflowId, uuidDecision);
                    recordedRandoms.put(workflowId, randomDecision);
                    snapshotUuids.put(workflowId, snapshot.getUuids());
                    snapshotRandoms.put(workflowId, snapshot.getRandoms().get("iso-" + workflowId));
                } finally {
                    workflowRuntime.clearDeterminismContext();
                    workflowRuntime.clearCurrentWorkflowId();
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "await isolation tasks");
        executor.shutdownNow();

        assertThat(recordedClocks).hasSize(workflowIds.size());
        for (String workflowId : workflowIds) {
            List<String> clocks = recordedClocks.get(workflowId);
            assertThat(clocks).hasSize(2);
            assertThat(clocks.get(0)).isEqualTo(baseTimes.get(workflowId).toString());
            assertThat(clocks.get(1)).isEqualTo(baseTimes.get(workflowId).plusSeconds(2).toString());

            UUID uuid = recordedUuids.get(workflowId);
            assertThat(uuid).isNotNull();
            List<String> uuidList = snapshotUuids.get(workflowId);
            assertThat(uuidList).containsExactly(uuid.toString());

            Long randomValue = recordedRandoms.get(workflowId);
            assertThat(randomValue).isNotNull();
            List<Long> randomList = snapshotRandoms.get(workflowId);
            assertThat(randomList).containsExactly(randomValue);
        }
    }

    @Test
    void testReplayWithDifferentUuids() {
        String workflowId = UUID.randomUUID().toString();
        List<UUID> recorded = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        DeterminismSnapshot snapshot = new DeterminismSnapshot();
        List<String> uuidStrings = recorded.stream().map(UUID::toString).toList();
        snapshot.setUuids(new ArrayList<>(uuidStrings));

        DeterminismContext context = new DeterminismContext();
        workflowRuntime.setCurrentWorkflowId(workflowId);
        workflowRuntime.setDeterminismContext(context);
        try {
            snapshot.applyTo(context.clock(), context.uuid(), context.random());
            DeterminismContext bound = workflowRuntime.getDeterminismContext();
            assertThat(bound.uuid().randomUUID()).isEqualTo(recorded.get(0));
            assertThat(bound.uuid().randomUUID()).isEqualTo(recorded.get(1));
            assertThat(bound.uuid().randomUUID()).isEqualTo(recorded.get(2));
        } finally {
            workflowRuntime.clearDeterminismContext();
            workflowRuntime.clearCurrentWorkflowId();
        }
    }

    @Test
    @Transactional
    void testOldWorkflowBackwardCompatibility() throws Exception {
        String workflowId = UUID.randomUUID().toString();
        WorkflowStateEntity state = prepareWorkflowState(workflowId, "RUNNING");
        String legacyJson = """
                {
                  "recordedTimes": [
                    "2024-12-01T00:00:00Z",
                    "2024-12-01T00:00:01Z"
                  ],
                  "replayIndex": 0,
                  "replayMode": true,
                  "version": 1
                }
                """;
        state.clockTimes = legacyJson;
        state.persist();

        eventStore.appendEvent(
                workflowId,
                WorkflowEvent.Type.WORKFLOW_COMPLETED,
                Map.of("result", "legacy-ok")
        );

        schedulerService.processWorkflow(workflowId);

        WorkflowStateEntity refreshed = reloadState(workflowId);
        assertThat(refreshed.status).isEqualTo("COMPLETED");
        assertThat(refreshed.clockTimes).isNotBlank();
        DeterminismSnapshot snapshot = mapper.readValue(refreshed.clockTimes, DeterminismSnapshot.class);
        assertThat(snapshot.getClockTimes()).containsExactly(
                "2024-12-01T00:00:00Z",
                "2024-12-01T00:00:01Z"
        );
    }

    @Test
    @Transactional
    void testDeterminismSnapshotPersistence() throws Exception {
        // 成功完成场景
        String completedWorkflow = UUID.randomUUID().toString();
        prepareWorkflowState(completedWorkflow, "RUNNING");
        DeterminismContext successContext = new DeterminismContext();
        workflowRuntime.setCurrentWorkflowId(completedWorkflow);
        workflowRuntime.setDeterminismContext(successContext);
        try {
            successContext.clock().recordTimeDecision(Instant.parse("2025-03-01T00:00:00Z"));
            UUID successUuid = successContext.uuid().randomUUID();
            long successRandom = successContext.random().nextLong("persist-success");
            workflowRuntime.completeWorkflow(completedWorkflow, Map.of("value", 1));

            WorkflowStateEntity completedState = reloadState(completedWorkflow);
            DeterminismSnapshot completedSnapshot = mapper.readValue(completedState.clockTimes, DeterminismSnapshot.class);
            assertThat(completedSnapshot.getUuids()).containsExactly(successUuid.toString());
            assertThat(completedSnapshot.getRandoms()).containsEntry("persist-success", List.of(successRandom));
        } finally {
            workflowRuntime.clearDeterminismContext();
            workflowRuntime.clearCurrentWorkflowId();
        }

        // 失败场景
        String failedWorkflow = UUID.randomUUID().toString();
        prepareWorkflowState(failedWorkflow, "RUNNING");
        DeterminismContext failedContext = new DeterminismContext();
        workflowRuntime.setCurrentWorkflowId(failedWorkflow);
        workflowRuntime.setDeterminismContext(failedContext);
        try {
            failedContext.clock().recordTimeDecision(Instant.parse("2025-03-02T00:00:00Z"));
            UUID failedUuid = failedContext.uuid().randomUUID();
            long failedRandom = failedContext.random().nextLong("persist-fail");
            workflowRuntime.failWorkflow(failedWorkflow, new IllegalStateException("boom"));

            WorkflowStateEntity failedState = reloadState(failedWorkflow);
            DeterminismSnapshot failedSnapshot = mapper.readValue(failedState.clockTimes, DeterminismSnapshot.class);
            assertThat(failedSnapshot.getUuids()).containsExactly(failedUuid.toString());
            assertThat(failedSnapshot.getRandoms()).containsEntry("persist-fail", List.of(failedRandom));
        } finally {
            workflowRuntime.clearDeterminismContext();
            workflowRuntime.clearCurrentWorkflowId();
        }
    }

    private WorkflowStateEntity prepareWorkflowState(String workflowId, String status) {
        WorkflowStateEntity state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseGet(WorkflowStateEntity::new);
        state.workflowId = UUID.fromString(workflowId);
        state.status = status;
        state.lastEventSeq = 0L;
        state.createdAt = Instant.now();
        state.updatedAt = Instant.now();
        state.persist();
        return state;
    }

    private WorkflowStateEntity reloadState(String workflowId) {
        return WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow(() -> new IllegalStateException("workflow state missing: " + workflowId));
    }
}
