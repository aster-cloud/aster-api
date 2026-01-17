package io.aster.audit.service;

import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.outbox.OutboxStatus;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnomalyWorkflowService 单元测试（Phase 3.7, Phase 3.8 扩展）
 *
 * 验证异常状态机编排逻辑：
 * - 提交验证动作
 * - 更新异常状态
 * - 记录验证结果
 * - Phase 3.8: payload 构建逻辑（有/无 sampleWorkflowId）
 */
@QuarkusTest
class AnomalyWorkflowServiceTest {

    @Inject
    AnomalyWorkflowService service;

    private Long testAnomalyId;
    private Long testVersionId;
    private String testPolicyId;

    @BeforeEach
    @Transactional
    void setUp() {
        // 创建测试策略版本
        testPolicyId = "test-policy-" + UUID.randomUUID();
        PolicyVersion version = new PolicyVersion();
        version.policyId = testPolicyId;
        version.version = Instant.now().toEpochMilli();
        version.moduleName = "test.module";
        version.functionName = "testFunction";
        version.content = "test content";
        version.active = true;
        version.createdAt = Instant.now();
        version.persist();

        testVersionId = version.id;

        // 创建测试异常报告
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "HIGH_FAILURE_RATE";
        anomaly.versionId = testVersionId;
        anomaly.policyId = testPolicyId;
        anomaly.severity = "CRITICAL";
        anomaly.status = "PENDING";
        anomaly.description = "Test anomaly";
        anomaly.recommendation = "Test recommendation";
        anomaly.metricValue = 0.5;
        anomaly.threshold = 0.1;
        anomaly.detectedAt = Instant.now();
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        testAnomalyId = anomaly.id;
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // 清理测试数据
        AnomalyActionEntity.delete("anomalyId = ?1", testAnomalyId);
        AnomalyReportEntity.deleteById(testAnomalyId);
        WorkflowStateEntity.delete("policyVersionId = ?1", testVersionId);
        PolicyVersion.deleteById(testVersionId);
    }

    @Test
    @Transactional
    void testSubmitVerificationAction_Success() {
        // Phase 3.8: 创建带 clockTimes 的 workflow
        UUID sampleWorkflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = sampleWorkflowId;
        workflow.policyVersionId = testVersionId;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        // 设置 anomaly 的 sampleWorkflowId
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        anomaly.sampleWorkflowId = sampleWorkflowId;
        anomaly.persist();

        // 执行提交验证动作
        Long actionId = service.submitVerificationAction(testAnomalyId)
            .await().indefinitely();

        // 验证动作创建成功
        assertNotNull(actionId);
        assertTrue(actionId > 0);

        // 验证异常状态更新为 VERIFYING
        anomaly = AnomalyReportEntity.findById(testAnomalyId);
        assertEquals("VERIFYING", anomaly.status);

        // 验证动作实体创建成功
        AnomalyActionEntity action = AnomalyActionEntity.findById(actionId);
        assertNotNull(action);
        assertEquals(testAnomalyId, action.anomalyId);
        assertEquals("VERIFY_REPLAY", action.actionType);
        assertEquals(OutboxStatus.PENDING, action.status);
    }

    @Test
    void testSubmitVerificationAction_AnomalyNotFound() {
        // 测试异常不存在的情况
        Long invalidId = 999999L;

        assertThrows(IllegalArgumentException.class, () -> {
            service.submitVerificationAction(invalidId)
                .await().indefinitely();
        });
    }

    @Test
    @Transactional
    void testUpdateStatus_ToResolved() {
        // 先设置状态为 VERIFIED
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        anomaly.status = "VERIFIED";
        anomaly.persist();

        // 更新状态为 RESOLVED
        Boolean result = service.updateStatus(testAnomalyId, "RESOLVED", "问题已解决")
            .await().indefinitely();

        assertTrue(result);

        // 验证状态更新成功
        anomaly = AnomalyReportEntity.findById(testAnomalyId);
        assertEquals("RESOLVED", anomaly.status);
        assertEquals("问题已解决", anomaly.resolutionNotes);
        assertNotNull(anomaly.resolvedAt);
    }

    @Test
    @Transactional
    void testUpdateStatus_ToDismissed() {
        // 更新状态为 DISMISSED
        Boolean result = service.updateStatus(testAnomalyId, "DISMISSED", "误报")
            .await().indefinitely();

        assertTrue(result);

        // 验证状态更新成功
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        assertEquals("DISMISSED", anomaly.status);
        assertEquals("误报", anomaly.resolutionNotes);
        assertNotNull(anomaly.resolvedAt);
    }

    @Test
    @Transactional
    void testRecordVerificationResult_Success() {
        // 构建验证结果
        VerificationResult result = new VerificationResult(
            true,  // replaySucceeded
            true,  // anomalyReproduced
            "test-workflow-id",
            Instant.now(),
            1000L,  // originalDurationMs
            1200L   // replayDurationMs
        );

        // 记录验证结果
        Boolean success = service.recordVerificationResult(testAnomalyId, result)
            .await().indefinitely();

        assertTrue(success);

        // 验证结果写入成功
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        assertEquals("VERIFIED", anomaly.status);
        assertNotNull(anomaly.verificationResult);
        assertTrue(anomaly.verificationResult.contains("test-workflow-id"));
        assertTrue(anomaly.verificationResult.contains("\"replaySucceeded\":true"));
        assertTrue(anomaly.verificationResult.contains("\"anomalyReproduced\":true"));
    }

    /**
     * Phase 3.8: 测试 payload 构建 - 有 sampleWorkflowId 和 clockTimes 的情况
     *
     * 验证：
     * 1. payload 正确构建为 JSON 格式
     * 2. payload 包含正确的 workflowId
     */
    @Test
    @Transactional
    void testSubmitVerificationAction_WithSampleWorkflowId_PayloadBuilt() {
        // 创建带 clockTimes 的 workflow
        UUID sampleWorkflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = sampleWorkflowId;
        workflow.policyVersionId = testVersionId;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        // 设置 sampleWorkflowId
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        anomaly.sampleWorkflowId = sampleWorkflowId;
        anomaly.persist();

        // 执行提交验证动作
        Long actionId = service.submitVerificationAction(testAnomalyId)
            .await().indefinitely();

        // 验证 payload 构建成功
        AnomalyActionEntity action = AnomalyActionEntity.findById(actionId);
        assertNotNull(action.payload, "payload 应该被构建");
        assertTrue(action.payload.contains("workflowId"), "payload 应包含 workflowId 字段");
        assertTrue(action.payload.contains(sampleWorkflowId.toString()),
            "payload 应包含正确的 workflow ID");
        assertTrue(action.payload.startsWith("{") && action.payload.endsWith("}"),
            "payload 应该是有效的 JSON");
    }

    /**
     * Phase 3.8: 测试跳过场景 - 无 sampleWorkflowId 时跳过创建
     *
     * 验证：
     * 1. 缺少 sampleWorkflowId 时返回 null
     * 2. 不创建 AnomalyActionEntity
     * 3. 状态保持 PENDING
     */
    @Test
    @Transactional
    void testSubmitVerificationAction_WithoutSampleWorkflowId_GracefulDegradation() {
        // 确保 sampleWorkflowId 为 null
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        anomaly.sampleWorkflowId = null;
        anomaly.persist();

        // 执行提交验证动作（Phase 3.8: 应返回 null）
        Long actionId = service.submitVerificationAction(testAnomalyId)
            .await().indefinitely();

        // 验证返回 null（跳过创建）
        assertNull(actionId, "缺少 sampleWorkflowId 时应返回 null");

        // 验证状态保持 PENDING（未更新）
        anomaly = AnomalyReportEntity.findById(testAnomalyId);
        assertEquals("PENDING", anomaly.status);
    }

    /**
     * Phase 3.8: 测试 payload JSON 格式正确性
     *
     * 验证 payload 是有效的 JSON 并可以被解析
     */
    @Test
    @Transactional
    void testSubmitVerificationAction_PayloadJsonFormat() {
        // 创建带 clockTimes 的 workflow
        UUID sampleWorkflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = sampleWorkflowId;
        workflow.policyVersionId = testVersionId;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        // 设置 sampleWorkflowId
        AnomalyReportEntity anomaly = AnomalyReportEntity.findById(testAnomalyId);
        anomaly.sampleWorkflowId = sampleWorkflowId;
        anomaly.persist();

        // 执行提交验证动作
        Long actionId = service.submitVerificationAction(testAnomalyId)
            .await().indefinitely();

        // 获取 payload
        AnomalyActionEntity action = AnomalyActionEntity.findById(actionId);
        String payload = action.payload;

        // 验证 JSON 格式（使用简单的字符串检查）
        assertNotNull(payload);
        assertTrue(payload.matches("\\{.*\"workflowId\"\\s*:\\s*\"[a-f0-9\\-]+\".*\\}"),
            "payload 应该是有效的 JSON，包含 workflowId 字段和 UUID 值");

        // 验证可以提取 workflowId
        assertTrue(payload.contains(sampleWorkflowId.toString()),
            "payload 应包含正确的 UUID 字符串");
    }
}
