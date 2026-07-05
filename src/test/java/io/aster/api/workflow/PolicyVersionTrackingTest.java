package io.aster.api.workflow;

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

    @Inject
    io.aster.policy.tenant.TenantContext tenantContext;

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

    /**
     * 安全审计 C1（同类）回归：workflow 版本追踪须按 workflow 租户查 active 版本。
     *
     * <p>policyId 非租户唯一——历史缺陷是 enrichPolicyVersion 用 tenantless getActiveVersion，
     * 多租户同 policyId 时会把**其它租户**的 active versionId 写进本租户 workflow_state（污染
     * 审计/analytics/异常检测）。本测试：同 policyId 下租户 A/B 各有一个 active 版本，以租户 A
     * 调度 workflow，其 workflow_state.policyVersionId 必须是**租户 A** 的版本。
     */
    @Test
    @Transactional
    void enrichPolicyVersionMustUseWorkflowTenantActiveVersion() {
        String sharedPolicyId = "aster.audit.wf-crosstenant";
        String tenantA = "wf-ct-a";
        String tenantB = "wf-ct-b";

        // 租户 A / B 各一个同 policyId 的 active 版本。
        Long aVersionId = seedActiveVersion(sharedPolicyId, tenantA, 1000L);
        Long bVersionId = seedActiveVersion(sharedPolicyId, tenantB, 2000L);

        String previousTenant = tenantContext.isInitialized() ? tenantContext.getCurrentTenant() : null;
        try {
            tenantContext.setCurrentTenant(tenantA);  // 以租户 A 身份调度

            String workflowId = UUID.randomUUID().toString();
            WorkflowMetadata metadata = new WorkflowMetadata();
            metadata.set(WorkflowMetadata.Keys.POLICY_ID, sharedPolicyId);
            workflowRuntime.schedule(workflowId, null, metadata);

            WorkflowStateEntity state = WorkflowStateEntity.findByWorkflowId(UUID.fromString(workflowId))
                .orElseThrow(() -> new AssertionError("WorkflowState not found"));

            assertEquals(aVersionId, state.policyVersionId,
                "C1 回归：workflow 应记录**本租户(A)** 的 active 版本");
            assertNotEquals(bVersionId, state.policyVersionId,
                "C1 回归：不得把租户 B 同 policyId 的版本写进租户 A 的 workflow_state");
        } finally {
            tenantContext.setCurrentTenant(previousTenant);
            PolicyVersion.delete("policyId = ?1", sharedPolicyId);
        }
    }

    private Long seedActiveVersion(String policyId, String tenantId, long version) {
        PolicyVersion v = new PolicyVersion();
        v.policyId = policyId;
        v.version = version;
        v.moduleName = "aster.audit";
        v.functionName = "wfCrossTenant";
        v.content = "// " + tenantId;
        v.tenantId = tenantId;
        v.active = true;
        v.status = io.aster.policy.entity.VersionStatus.APPROVED;
        v.createdAt = java.time.Instant.now();
        v.persist();
        return v.id;
    }
}
