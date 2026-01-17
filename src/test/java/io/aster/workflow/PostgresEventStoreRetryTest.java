package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PostgresEventStoreRetryTest {

    @Inject
    PostgresEventStore eventStore;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanup() {
        WorkflowTimerEntity.deleteAll();
        WorkflowEventEntity.deleteAll();
        WorkflowStateEntity.deleteAll();
    }

    @Test
    @Transactional
    void testAppendEventWithRetryMetadata() {
        String workflowId = UUID.randomUUID().toString();

        long seq = eventStore.appendEvent(
                workflowId,
                WorkflowEvent.Type.WORKFLOW_FAILED,
                Map.of("error", "boom"),
                3,
                1500L,
                "network-timeout"
        );

        assertThat(seq).isEqualTo(1L);

        WorkflowEventEntity stored = WorkflowEventEntity.find("workflowId", UUID.fromString(workflowId))
                .<WorkflowEventEntity>firstResult();
        assertThat(stored).isNotNull();
        assertThat(stored.attemptNumber).isEqualTo(3);
        assertThat(stored.backoffDelayMs).isEqualTo(1500L);
        assertThat(stored.failureReason).isEqualTo("network-timeout");
    }

    @Test
    @Transactional
    void testQueryEventsByAttempt() {
        String workflowId = UUID.randomUUID().toString();

        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_STARTED, Map.of("status", "STARTED"), 1, null, null);
        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_FAILED, Map.of("error", "x"), 2, 500L, "retry-1");

        List<WorkflowEventEntity> attempt1 = WorkflowEventEntity.findByWorkflowIdAndAttempt(UUID.fromString(workflowId), 1);
        List<WorkflowEventEntity> attempt2 = WorkflowEventEntity.findByWorkflowIdAndAttempt(UUID.fromString(workflowId), 2);

        assertThat(attempt1).hasSize(1);
        assertThat(attempt2).hasSize(1);
        assertThat(attempt2.get(0).failureReason).isEqualTo("retry-1");
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void testSnapshotWithRetryContext() throws Exception {
        String workflowId = UUID.randomUUID().toString();

        eventStore.appendEvent(workflowId, WorkflowEvent.Type.WORKFLOW_STARTED, Map.of("status", "STARTED"));

        WorkflowStateEntity state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId)).orElseThrow();
        state.setRetryContext(Map.of("currentAttempt", 2, "lastBackoff", 2000L));
        state.persist();

        eventStore.saveSnapshot(
                workflowId,
                1L,
                Map.of("lastEventSeq", 1L, "status", state.status)
        );

        WorkflowStateEntity refreshed = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId)).orElseThrow();
        Map<String, Object> snapshot = objectMapper.readValue(
                refreshed.snapshot,
                new TypeReference<Map<String, Object>>() {}
        );

        assertThat(snapshot).containsEntry("lastEventSeq", 1);
        assertThat(snapshot).containsEntry("status", state.status);
        assertThat(snapshot).containsKey("retry_context");

        Map<String, Object> retryContext = (Map<String, Object>) snapshot.get("retry_context");
        assertThat(retryContext.get("currentAttempt")).isEqualTo(2);
        assertThat(retryContext.get("lastBackoff")).isEqualTo(2000);
    }

    @Test
    @Transactional
    void testBackwardCompatibility() {
        String workflowId = UUID.randomUUID().toString();

        long seq = eventStore.appendEvent(
                workflowId,
                WorkflowEvent.Type.WORKFLOW_STARTED,
                Map.of("status", "STARTED")
        );

        assertThat(seq).isEqualTo(1L);

        Optional<WorkflowEventEntity> stored = WorkflowEventEntity.find("workflowId", UUID.fromString(workflowId))
                .firstResultOptional();

        assertThat(stored).isPresent();
        assertThat(stored.get().attemptNumber).isEqualTo(1);
        assertThat(stored.get().backoffDelayMs).isNull();
        assertThat(stored.get().failureReason).isNull();
    }
}
