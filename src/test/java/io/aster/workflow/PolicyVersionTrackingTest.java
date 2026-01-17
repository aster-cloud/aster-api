package io.aster.workflow;

import aster.runtime.workflow.WorkflowMetadata;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 策略版本追踪单元测试（Phase 3.1）
 *
 * 测试策略版本信息在 workflow 执行过程中的正确注入和记录。
 */
@QuarkusTest
public class PolicyVersionTrackingTest {

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    PostgresEventStore eventStore;

    @Inject
    PolicyVersionService policyVersionService;

    private String testPolicyId;
    private PolicyVersion testVersion;

    @BeforeEach
    @Transactional
    void setUp() {
        // 创建测试策略版本
        testPolicyId = "aster.test.samplePolicy";
        testVersion = policyVersionService.createVersion(
                testPolicyId,
                "aster.test",
                "samplePolicy",
                "function samplePolicy() { return \"test\"; }",
                "test-user",
                "Test policy for Phase 3.1"
        );
    }

    @Test
    @Transactional
    void testScheduleWorkflowWithPolicyVersion() {
        // Given: Workflow metadata 包含 policyId
        String workflowId = UUID.randomUUID().toString();
        WorkflowMetadata metadata = new WorkflowMetadata();
        metadata.set(WorkflowMetadata.Keys.POLICY_ID, testPolicyId);

        // When: 调度 workflow
        workflowRuntime.schedule(workflowId, "test-idempotency-key", metadata);

        // Then: WorkflowState 应记录 policyVersionId
        Optional<WorkflowStateEntity> stateOpt = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId));
        assertTrue(stateOpt.isPresent(), "WorkflowState should exist");

        WorkflowStateEntity state = stateOpt.get();
        assertNotNull(state.policyVersionId, "policyVersionId should be set");
        assertEquals(testVersion.id, state.policyVersionId, "policyVersionId should match active version");
        assertNotNull(state.policyActivatedAt, "policyActivatedAt should be set");

        // Then: Metadata 应包含完整版本信息
        Long versionId = metadata.getPolicyVersionId();
        assertNotNull(versionId, "metadata should contain policyVersionId");
        assertEquals(testVersion.id, versionId, "metadata policyVersionId should match");
    }

    @Test
    @Transactional
    void testEventStorePolicyVersionId() {
        // Given: WorkflowState 已设置 policyVersionId
        String workflowId = UUID.randomUUID().toString();
        UUID wfId = UUID.fromString(workflowId);

        WorkflowStateEntity state = WorkflowStateEntity.getOrCreate(wfId, "test-tenant");
        state.policyVersionId = testVersion.id;
        state.policyActivatedAt = java.time.Instant.now();
        state.persist();

        // When: 追加事件
        long eventSeq = eventStore.appendEvent(workflowId, "WorkflowStarted", "{\"test\": true}");

        // Then: 事件应记录 policyVersionId
        Optional<WorkflowEventEntity> eventOpt = WorkflowEventEntity.find("workflowId = ?1 AND sequence = ?2", wfId, eventSeq)
                .firstResultOptional();
        assertTrue(eventOpt.isPresent(), "Event should exist");

        WorkflowEventEntity event = eventOpt.get();
        assertNotNull(event.policyVersionId, "Event policyVersionId should be set");
        assertEquals(testVersion.id, event.policyVersionId, "Event policyVersionId should match state");
    }

    @Test
    @Transactional
    void testWorkflowStateRecordsPolicyVersion() {
        // Given: Workflow metadata 包含 policyId
        String workflowId = UUID.randomUUID().toString();
        WorkflowMetadata metadata = new WorkflowMetadata();
        metadata.set(WorkflowMetadata.Keys.POLICY_ID, testPolicyId);

        // When: 调度 workflow
        workflowRuntime.schedule(workflowId, null, metadata);

        // Then: 验证 WorkflowState 版本字段
        WorkflowStateEntity state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow(() -> new AssertionError("WorkflowState not found"));

        assertEquals(testVersion.id, state.policyVersionId, "WorkflowState should record correct policyVersionId");
        assertNotNull(state.policyActivatedAt, "WorkflowState should record activation timestamp");

        // Then: 验证可通过 policyVersionId 查询 workflow
        long count = WorkflowStateEntity.count("policyVersionId = ?1", testVersion.id);
        assertTrue(count > 0, "Should be able to query workflows by policyVersionId");
    }

    @Test
    @Transactional
    void testLegacyWorkflowWithoutVersion() {
        // Given: Workflow metadata 不包含 policyId（模拟版本化功能上线前的 workflow）
        String workflowId = UUID.randomUUID().toString();
        WorkflowMetadata metadata = new WorkflowMetadata();
        // 不设置 policyId

        // When: 调度 workflow
        workflowRuntime.schedule(workflowId, null, metadata);

        // Then: WorkflowState 的 policyVersionId 应为 NULL
        WorkflowStateEntity state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow(() -> new AssertionError("WorkflowState not found"));

        assertNull(state.policyVersionId, "Legacy workflow should have NULL policyVersionId");
        assertNull(state.policyActivatedAt, "Legacy workflow should have NULL policyActivatedAt");

        // Then: 事件的 policyVersionId 也应为 NULL
        long count = WorkflowEventEntity.count("workflowId = ?1 AND policyVersionId IS NULL", UUID.fromString(workflowId));
        assertTrue(count > 0, "Legacy workflow events should have NULL policyVersionId");
    }
}
