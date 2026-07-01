package io.aster.policy.service;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.audit.service.PolicyAuditService;
import io.aster.billing.PlanGateService;
import io.aster.billing.PlanInfo;
import io.aster.billing.PlanLimitException;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.workflow.WorkflowStateEntity;
import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import io.aster.test.PostgresTestResource;
import io.aster.test.telemetry.NsmTelemetryAssertions;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * PolicyVersionService 工作流端到端集成测试
 *
 * 覆盖：
 *   1. activateVersion 成功后触发 NsmEvents.DRAFT_PUBLISHED
 *   2. submitForApproval 在 Free 档（plan gate 拒绝）抛 PlanLimitException
 *   3. approveVersion 在 Free 档抛 PlanLimitException（防御深度）
 *
 * 使用 @InjectMock 替换 NsmTelemetry / PlanGateService，验证调用契约。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
class PolicyVersionWorkflowIT {

    @Inject
    PolicyVersionService versionService;

    @Inject
    PolicyAuditService auditService;

    @InjectMock
    NsmTelemetry nsmTelemetry;

    @InjectMock
    PlanGateService planGate;

    @BeforeEach
    void resetMocks() {
        reset(nsmTelemetry, planGate);
        // 默认 plan-gate 放行（Pro 档），具体测试再覆盖
        when(planGate.allowsApproval(anyString())).thenReturn(true);
        when(planGate.lookupPlan(anyString())).thenReturn(PlanInfo.failOpen());
    }

    @Test
    @Transactional
    void activateVersion_emitsDraftPublishedEvent() {
        // 准备一个 APPROVED 状态的版本
        PolicyVersion v = new PolicyVersion(
            "it-wf-pub-1", "aster.test", "fn1",
            "Module aster.test.\nRule fn1 given x: Return x.",
            "tester", null);
        v.tenantId = "tenant-pub-1";
        v.sourceKind = "ai_draft_edited";
        v.authorRole = "business_expert";  // v1.2
        v.status = VersionStatus.APPROVED;
        v.locale = "zh";
        v.persist();

        versionService.activateVersion(v.id, "approver-1");

        // 用统一的 NSM 断言工具：覆盖 emittedByServer + 关键 prop
        NsmTelemetryAssertions.assertEvent(nsmTelemetry, NsmEvents.DRAFT_PUBLISHED, "approver-1")
            .withProp("rule_id", "it-wf-pub-1")
            .withProp("source_kind", "ai_draft_edited")
            .withProp("tenant_id", "tenant-pub-1")
            .withProp("reviewer_id", "approver-1")
            .withProp("author_role", "business_expert")  // v1.2
            .emittedByServer();
    }

    @Test
    @Transactional
    void submitForApproval_freeTenant_throwsPlanLimit() {
        when(planGate.allowsApproval("tenant-free-1")).thenReturn(false);

        PolicyVersion v = new PolicyVersion(
            "it-wf-free-1", "aster.test", "fn2",
            "Module aster.test.\nRule fn2 given x: Return x.",
            "tester", null);
        v.tenantId = "tenant-free-1";
        v.status = VersionStatus.DRAFT;
        v.locale = "zh";
        v.persist();
        final Long vid = v.id;

        PlanLimitException ex = assertThrows(PlanLimitException.class,
            () -> versionService.submitForApproval(vid, "author-1"));
        assertEquals("reviewer_required", ex.reason());

        // 没有触发 NsmTelemetry（因为 plan gate 拦在前面）
        NsmTelemetryAssertions.assertNoEvent(nsmTelemetry, NsmEvents.DRAFT_PUBLISHED);
    }

    @Test
    @Transactional
    void approveVersion_freeTenant_throwsPlanLimit_defenseInDepth() {
        when(planGate.allowsApproval("tenant-free-2")).thenReturn(false);

        PolicyVersion v = new PolicyVersion(
            "it-wf-free-2", "aster.test", "fn3",
            "Module aster.test.\nRule fn3 given x: Return x.",
            "tester", null);
        v.tenantId = "tenant-free-2";
        // 直接构造 SUBMITTED 状态，模拟 submitForApproval 没拦住的情况
        v.status = VersionStatus.SUBMITTED;
        v.submittedBy = "author-2";
        v.submittedAt = Instant.now();
        v.locale = "zh";
        v.persist();
        final Long vid = v.id;

        PlanLimitException ex = assertThrows(PlanLimitException.class,
            () -> versionService.approveVersion(vid, "approver-2"));
        assertEquals("reviewer_required", ex.reason(),
            "approveVersion 必须有防御深度的 plan gate 检查");
    }

    @Test
    @Transactional
    void approveVersion_proTenant_succeedsAndKeepsState() {
        when(planGate.allowsApproval("tenant-pro-1")).thenReturn(true);

        PolicyVersion v = new PolicyVersion(
            "it-wf-pro-1", "aster.test", "fn4",
            "Module aster.test.\nRule fn4 given x: Return x.",
            "tester", null);
        v.tenantId = "tenant-pro-1";
        v.status = VersionStatus.SUBMITTED;
        v.submittedBy = "author-3";
        v.submittedAt = Instant.now();
        v.locale = "zh";
        v.persist();

        PolicyVersion result = versionService.approveVersion(v.id, "approver-3");
        assertNotNull(result);
        assertEquals(VersionStatus.APPROVED, result.status);
        assertEquals("approver-3", result.approvedBy);
    }

    /**
     * G6：版本来源 sourceKind 必须在审计导出（VersionHistoryDTO）里可见，
     * 不能仅存于 DB 列/metadata。合规消费者据此识别 AI 起草来源。
     */
    @Test
    @Transactional
    void versionHistory_exportsSourceKind() {
        PolicyVersion v = new PolicyVersion(
            "it-wf-srckind-1", "aster.test", "fn5",
            "Module aster.test.\nRule fn5 given x: Return x.",
            "tester", null);
        v.tenantId = "tenant-srckind-1";
        v.sourceKind = "ai_draft";
        v.status = VersionStatus.APPROVED;
        v.locale = "zh";
        v.persist();

        java.util.UUID workflowId = java.util.UUID.randomUUID();
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = workflowId;
        state.status = "COMPLETED";
        state.tenantId = "tenant-srckind-1";
        state.policyVersionId = v.id;
        state.policyActivatedAt = Instant.now();
        state.persist();

        var history = auditService.getWorkflowVersionHistory(workflowId, "tenant-srckind-1");
        assertEquals(1, history.size());
        assertEquals("ai_draft", history.get(0).sourceKind,
            "审计导出必须暴露版本来源 sourceKind（G6）");
    }
}
