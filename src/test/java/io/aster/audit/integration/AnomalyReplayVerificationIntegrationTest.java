package io.aster.audit.integration;

import io.aster.audit.dto.AnomalyReportDTO;
import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.outbox.OutboxStatus;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.audit.service.AnomalyActionExecutor;
import io.aster.audit.service.AnomalyWorkflowService;
import io.aster.audit.service.PolicyAnalyticsService;
import io.aster.policy.entity.PolicyVersion;
import io.aster.workflow.WorkflowSchedulerService;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anomaly Replay 验证链路集成测试（Phase 3.8）
 *
 * 端到端验证完整的异常响应自动化流程：
 * 1. 异常检测（PolicyAnalyticsService）→ 捕获 sampleWorkflowId
 * 2. 持久化异常报告（AnomalyReportEntity）
 * 3. 提交验证动作（AnomalyWorkflowService）→ 构建 payload
 * 4. 执行 Replay 验证（AnomalyActionExecutor）→ 调用 replayWorkflow()
 * 5. 记录验证结果（写入 verification_result）
 *
 * 注意：由于完整的 workflow 执行需要实际的 policy 编译和运行环境，
 * 这个集成测试主要验证数据流完整性和各组件集成正确性。
 * 实际的 workflow replay 执行在 E2E 测试中验证。
 */
@QuarkusTest
class AnomalyReplayVerificationIntegrationTest {

    @Inject
    PolicyAnalyticsService analyticsService;

    @Inject
    AnomalyWorkflowService workflowService;

    @Inject
    AnomalyActionExecutor actionExecutor;

    @Inject
    WorkflowSchedulerService schedulerService;

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
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // 清理测试数据（按依赖顺序）
        // 1. 清理 anomaly_reports（会级联删除 anomaly_actions）
        AnomalyReportEntity.delete("policyId = ?1", testPolicyId);

        // 3. 清理 workflow_state
        WorkflowStateEntity.delete("policyVersionId = ?1", testVersionId);

        // 4. 清理 policy_versions
        PolicyVersion.deleteById(testVersionId);
    }

    /**
     * 测试完整的异常检测 → payload 构建链路
     *
     * 验证：
     * 1. 异常检测能正确捕获 sampleWorkflowId
     * 2. 异常持久化后 sampleWorkflowId 正确存储
     * 3. 提交验证动作时 payload 正确构建
     */
    @Test
    @Transactional
    void testEndToEnd_AnomalyDetection_To_PayloadBuilding() {
        // 1. 准备测试数据：创建高失败率场景
        Instant now = Instant.now();
        UUID expectedSampleWorkflowId = null;

        for (int i = 0; i < 10; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.HOURS);
            workflow.status = (i % 2 == 0) ? "COMPLETED" : "FAILED";
            workflow.durationMs = 1000L;
            workflow.clockTimes = "[1000, 2000, 3000]";  // Phase 3.8: 添加 clockTimes
            workflow.persist();

            // 记录最新的失败 workflow（应该被捕获为 sampleWorkflowId）
            if ("FAILED".equals(workflow.status) && expectedSampleWorkflowId == null) {
                expectedSampleWorkflowId = workflow.workflowId;
            }
        }

        // 2. 执行异常检测
        List<AnomalyReportDTO> anomalies = analyticsService.detectAnomalies(0.3, 30);

        // 验证检测到异常
        AnomalyReportDTO detectedAnomaly = anomalies.stream()
            .filter(a -> testPolicyId.equals(a.policyId))
            .findFirst()
            .orElse(null);

        assertNotNull(detectedAnomaly, "应该检测到异常");
        assertNotNull(detectedAnomaly.sampleWorkflowId, "应该捕获 sampleWorkflowId");
        assertEquals(expectedSampleWorkflowId, detectedAnomaly.sampleWorkflowId,
            "sampleWorkflowId 应该是最新的失败 workflow");

        // 3. 持久化异常报告
        AnomalyReportEntity anomalyEntity = new AnomalyReportEntity();
        anomalyEntity.anomalyType = detectedAnomaly.anomalyType;
        anomalyEntity.versionId = detectedAnomaly.versionId;
        anomalyEntity.policyId = detectedAnomaly.policyId;
        anomalyEntity.metricValue = detectedAnomaly.metricValue;
        anomalyEntity.threshold = detectedAnomaly.threshold;
        anomalyEntity.severity = detectedAnomaly.severity;
        anomalyEntity.description = detectedAnomaly.description;
        anomalyEntity.recommendation = detectedAnomaly.recommendation;
        anomalyEntity.detectedAt = detectedAnomaly.detectedAt;
        anomalyEntity.sampleWorkflowId = detectedAnomaly.sampleWorkflowId;  // Phase 3.8
        anomalyEntity.status = "PENDING";
        anomalyEntity.tenantId = "test-tenant";
        anomalyEntity.persist();

        Long anomalyId = anomalyEntity.id;

        // 验证持久化成功
        AnomalyReportEntity persistedAnomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals(expectedSampleWorkflowId, persistedAnomaly.sampleWorkflowId,
            "持久化后 sampleWorkflowId 应保持一致");

        // 4. 提交验证动作
        Long actionId = workflowService.submitVerificationAction(anomalyId)
            .await().indefinitely();

        // 验证 payload 构建成功
        AnomalyActionEntity action = AnomalyActionEntity.findById(actionId);
        assertNotNull(action.payload, "payload 应该被构建");
        assertTrue(action.payload.contains("workflowId"), "payload 应包含 workflowId");
        assertTrue(action.payload.contains(expectedSampleWorkflowId.toString()),
            "payload 应包含正确的 workflow ID");

        // 验证异常状态更新
        persistedAnomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals("VERIFYING", persistedAnomaly.status, "状态应更新为 VERIFYING");
    }

    /**
     * 测试降级场景：无 sampleWorkflowId 时的优雅处理（Phase 3.8 更新）
     *
     * 验证：
     * 1. 缺少 sampleWorkflowId 时跳过创建验证动作
     * 2. 返回 null（不创建 action）
     * 3. 状态保持 PENDING（不更新）
     */
    @Test
    @Transactional
    void testEndToEnd_GracefulDegradation_WithoutSampleWorkflowId() {
        // 1. 创建异常报告但不设置 sampleWorkflowId
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "HIGH_FAILURE_RATE";
        anomaly.versionId = testVersionId;
        anomaly.policyId = testPolicyId;
        anomaly.metricValue = 0.5;
        anomaly.threshold = 0.3;
        anomaly.severity = "CRITICAL";
        anomaly.description = "Test anomaly";
        anomaly.recommendation = "Test recommendation";
        anomaly.detectedAt = Instant.now();
        anomaly.status = "PENDING";
        anomaly.sampleWorkflowId = null;  // 缺少 sampleWorkflowId
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        Long anomalyId = anomaly.id;

        // 2. 提交验证动作应该返回 null（Phase 3.8: 跳过创建）
        Long actionId = assertDoesNotThrow(() -> {
            return workflowService.submitVerificationAction(anomalyId)
                .await().indefinitely();
        }, "缺少 sampleWorkflowId 时应优雅跳过");

        // 3. 验证返回 null（未创建 action）
        assertNull(actionId, "缺少 sampleWorkflowId 时应返回 null");

        // 4. 验证状态保持 PENDING（未更新）
        anomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals("PENDING", anomaly.status, "缺少 sampleWorkflowId 时状态应保持 PENDING");
    }

    /**
     * 测试 Replay 验证流程 - clock_times 缺失场景（Phase 3.8 更新）
     *
     * 验证：
     * 1. submitVerificationAction() 检测到 clockTimes 缺失
     * 2. 跳过创建验证动作，返回 null
     * 3. 状态保持 PENDING
     */
    @Test
    @Transactional
    void testEndToEnd_ReplayVerification_MissingClockTimes() {
        // 1. 创建 workflow（无 clock_times）
        UUID workflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = workflowId;
        workflow.policyVersionId = testVersionId;
        workflow.startedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        workflow.status = "FAILED";
        workflow.errorMessage = "Test error";
        workflow.durationMs = 1000L;
        workflow.clockTimes = null;  // 缺少 clock_times
        workflow.persist();

        // 2. 创建异常报告
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "HIGH_FAILURE_RATE";
        anomaly.versionId = testVersionId;
        anomaly.policyId = testPolicyId;
        anomaly.metricValue = 0.5;
        anomaly.threshold = 0.3;
        anomaly.severity = "CRITICAL";
        anomaly.description = "Test anomaly";
        anomaly.recommendation = "Test recommendation";
        anomaly.detectedAt = Instant.now();
        anomaly.status = "PENDING";
        anomaly.sampleWorkflowId = workflowId;
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        Long anomalyId = anomaly.id;

        // 3. Phase 3.8: 提交验证动作时检测到 clockTimes 缺失，应跳过创建
        Long actionId = assertDoesNotThrow(() -> {
            return workflowService.submitVerificationAction(anomalyId)
                .await().indefinitely();
        }, "缺少 clockTimes 时应优雅跳过");

        // 4. 验证返回 null（未创建 action）
        assertNull(actionId, "缺少 clockTimes 时应返回 null");

        // 5. 验证状态保持 PENDING（未更新）
        AnomalyReportEntity persistedAnomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals("PENDING", persistedAnomaly.status, "缺少 clockTimes 时状态应保持 PENDING");
    }

    /**
     * 测试 PERFORMANCE_DEGRADATION 异常的完整成功流程（Phase 3.8 新增）
     *
     * 验证：
     * 1. PERFORMANCE_DEGRADATION 查询捕获 sampleWorkflowId
     * 2. submitVerificationAction() 验证 clockTimes 存在
     * 3. 成功创建验证动作并构建 payload
     * 4. 状态更新为 VERIFYING
     */
    @Test
    @Transactional
    void testEndToEnd_PerformanceDegradation_WithSampleWorkflowAndClockTimes() {
        // 1. 创建 COMPLETED workflows 用于 PERFORMANCE_DEGRADATION 检测
        Instant now = Instant.now();
        UUID expectedSampleWorkflowId = null;

        // 创建 10 个最近7天内的慢 workflows（平均 3000ms）
        for (int i = 0; i < 10; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.HOURS);  // 最近几小时
            workflow.status = "COMPLETED";
            workflow.durationMs = 3000L;  // 慢
            workflow.clockTimes = "[1000, 2000, 3000]";  // Phase 3.8: 包含 clockTimes
            workflow.persist();

            // 记录最新的 workflow（应该被捕获为 sampleWorkflowId）
            if (i == 0) {
                expectedSampleWorkflowId = workflow.workflowId;
            }
        }

        // 创建 10 个历史（7-30天前）的快 workflows（平均 1000ms）
        for (int i = 8; i < 18; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.DAYS);  // 8-17天前
            workflow.status = "COMPLETED";
            workflow.durationMs = 1000L;  // 快（3000/1000 = 3x > 1.5x 阈值）
            workflow.clockTimes = "[1000, 2000]";
            workflow.persist();
        }

        // 2. 执行异常检测
        List<AnomalyReportDTO> anomalies = analyticsService.detectAnomalies(0.3, 30);

        // 验证检测到 PERFORMANCE_DEGRADATION
        AnomalyReportDTO detectedAnomaly = anomalies.stream()
            .filter(a -> testPolicyId.equals(a.policyId))
            .filter(a -> "PERFORMANCE_DEGRADATION".equals(a.anomalyType))
            .findFirst()
            .orElse(null);

        assertNotNull(detectedAnomaly, "应该检测到 PERFORMANCE_DEGRADATION 异常");
        assertNotNull(detectedAnomaly.sampleWorkflowId,
            "Phase 3.8: PERFORMANCE_DEGRADATION 应该捕获 sampleWorkflowId");
        assertEquals(expectedSampleWorkflowId, detectedAnomaly.sampleWorkflowId,
            "sampleWorkflowId 应该是最新的 COMPLETED workflow");

        // 3. 持久化异常报告
        AnomalyReportEntity anomalyEntity = new AnomalyReportEntity();
        anomalyEntity.anomalyType = detectedAnomaly.anomalyType;
        anomalyEntity.versionId = detectedAnomaly.versionId;
        anomalyEntity.policyId = detectedAnomaly.policyId;
        anomalyEntity.metricValue = detectedAnomaly.metricValue;
        anomalyEntity.threshold = detectedAnomaly.threshold;
        anomalyEntity.severity = detectedAnomaly.severity;
        anomalyEntity.description = detectedAnomaly.description;
        anomalyEntity.recommendation = detectedAnomaly.recommendation;
        anomalyEntity.detectedAt = detectedAnomaly.detectedAt;
        anomalyEntity.sampleWorkflowId = detectedAnomaly.sampleWorkflowId;
        anomalyEntity.status = "PENDING";
        anomalyEntity.tenantId = "test-tenant";
        anomalyEntity.persist();

        Long anomalyId = anomalyEntity.id;

        // 4. 提交验证动作（Phase 3.8: 应通过 clockTimes 验证）
        Long actionId = workflowService.submitVerificationAction(anomalyId)
            .await().indefinitely();

        // 5. 验证成功创建 action
        assertNotNull(actionId, "Phase 3.8: 有 sampleWorkflowId 和 clockTimes 应创建 action");

        AnomalyActionEntity action = AnomalyActionEntity.findById(actionId);
        assertNotNull(action.payload, "payload 应该被构建");
        assertTrue(action.payload.contains("workflowId"), "payload 应包含 workflowId");
        assertTrue(action.payload.contains(expectedSampleWorkflowId.toString()),
            "payload 应包含正确的 workflow ID");
        assertEquals("VERIFY_REPLAY", action.actionType, "actionType 应为 VERIFY_REPLAY");
        assertEquals(OutboxStatus.PENDING, action.status, "status 应为 PENDING");

        // 6. 验证异常状态更新
        AnomalyReportEntity persistedAnomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals("VERIFYING", persistedAnomaly.status,
            "Phase 3.8: 成功创建 action 后状态应更新为 VERIFYING");
    }

    /**
     * 测试验证结果记录流程
     *
     * 验证：
     * 1. 验证结果正确序列化为 JSON
     * 2. 写入 anomaly_reports.verification_result
     * 3. 状态更新为 VERIFIED
     */
    @Test
    @Transactional
    void testEndToEnd_VerificationResultRecording() {
        // 1. 创建异常报告
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "HIGH_FAILURE_RATE";
        anomaly.versionId = testVersionId;
        anomaly.policyId = testPolicyId;
        anomaly.metricValue = 0.5;
        anomaly.threshold = 0.3;
        anomaly.severity = "CRITICAL";
        anomaly.description = "Test anomaly";
        anomaly.recommendation = "Test recommendation";
        anomaly.detectedAt = Instant.now();
        anomaly.status = "VERIFYING";
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        Long anomalyId = anomaly.id;

        // 2. 构建验证结果
        UUID testWorkflowId = UUID.randomUUID();
        VerificationResult result = new VerificationResult(
            true,   // replaySucceeded
            true,   // anomalyReproduced
            testWorkflowId.toString(),
            Instant.now(),
            1000L,  // originalDurationMs
            1050L   // replayDurationMs
        );

        // 3. 记录验证结果
        Boolean success = workflowService.recordVerificationResult(anomalyId, result)
            .await().indefinitely();

        assertTrue(success, "记录验证结果应成功");

        // 4. 验证结果写入成功
        anomaly = AnomalyReportEntity.findById(anomalyId);
        assertEquals("VERIFIED", anomaly.status, "状态应更新为 VERIFIED");
        assertNotNull(anomaly.verificationResult, "验证结果应被写入");
        assertTrue(anomaly.verificationResult.contains(testWorkflowId.toString()),
            "验证结果应包含 workflow ID");
        assertTrue(anomaly.verificationResult.contains("\"replaySucceeded\":true"),
            "验证结果应包含 replaySucceeded");
        assertTrue(anomaly.verificationResult.contains("\"anomalyReproduced\":true"),
            "验证结果应包含 anomalyReproduced");
    }

    /**
     * Phase 3.8 Task 2: 测试 AUTO_ROLLBACK 自动回滚成功场景
     *
     * 验证：
     * 1. 创建2个版本（v1历史版本、v2问题版本）
     * 2. 异常验证结果 anomalyReproduced=true 时自动创建 AUTO_ROLLBACK 动作
     * 3. payload 包含正确的 targetVersion（v1.version）
     * 4. 执行回滚后，活跃版本切换为 v1
     */
    @Test
    @Transactional
    void testEndToEnd_AutoRollback_AnomalyReproduced() {
        // 1. 创建 v1（历史正常版本）
        PolicyVersion v1 = new PolicyVersion();
        v1.policyId = testPolicyId;
        v1.version = Instant.now().minusSeconds(3600).toEpochMilli();
        v1.moduleName = "test.module";
        v1.functionName = "testFunction";
        v1.content = "v1 content";
        v1.active = false;
        v1.createdAt = Instant.now().minusSeconds(3600);
        v1.persist();

        // 2. 更新 v2（问题版本，当前活跃）- setUp() 已创建
        PolicyVersion v2 = PolicyVersion.findById(testVersionId);
        // v2.version 已在 setUp() 中设置为当前时间，应该大于 v1.version
        v2.content = "v2 content with bugs";
        v2.persist();

        // 确保 v2.version > v1.version
        assertTrue(v2.version > v1.version,
            "v2.version (" + v2.version + ") 应该大于 v1.version (" + v1.version + ")");

        // 3. 创建异常报告（指向 v2）
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "PERFORMANCE_DEGRADATION";
        anomaly.versionId = v2.id;
        anomaly.policyId = testPolicyId;
        anomaly.severity = "CRITICAL";
        anomaly.status = "PENDING";
        anomaly.description = "Performance degraded in v2";
        anomaly.detectedAt = Instant.now();
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        // 4. 创建 workflow（用于 replay）
        UUID workflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = workflowId;
        workflow.policyVersionId = v2.id;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        // 5. 设置 anomaly 的 sampleWorkflowId
        anomaly.sampleWorkflowId = workflowId;
        anomaly.persist();

        // 6. 记录验证结果（anomalyReproduced=true 触发回滚）
        VerificationResult result = new VerificationResult(
            true,  // replaySucceeded
            true,  // anomalyReproduced ← 触发回滚
            workflowId.toString(),
            Instant.now(),
            1000L, // originalDurationMs
            1200L  // replayDurationMs
        );

        Boolean success = workflowService.recordVerificationResult(anomaly.id, result)
            .await().indefinitely();

        assertTrue(success, "记录验证结果应成功");

        // 7. 验证 AUTO_ROLLBACK 动作已创建
        List<AnomalyActionEntity> actions = AnomalyActionEntity
            .find("anomalyId = ?1 and actionType = 'AUTO_ROLLBACK'", anomaly.id)
            .list();

        assertEquals(1, actions.size(), "应该创建1个 AUTO_ROLLBACK 动作");
        AnomalyActionEntity action = actions.get(0);
        assertEquals(OutboxStatus.PENDING, action.status, "动作状态应为 PENDING");
        assertNotNull(action.payload, "payload 应该存在");
        assertTrue(action.payload.contains("\"targetVersion\":" + v1.version),
            "payload 应包含 v1 的 version: " + v1.version);

        // 8. 执行回滚动作
        actionExecutor.executeAutoRollback(action).await().indefinitely();

        // 9. 验证版本切换成功
        PolicyVersion activeVersion = PolicyVersion.findActiveVersion(testPolicyId);
        assertNotNull(activeVersion, "应该有活跃版本");
        assertEquals(v1.version, activeVersion.version, "应该回滚到 v1");
        assertTrue(activeVersion.active, "v1 应该是活跃状态");

        // 10. 验证 v2 已停用
        PolicyVersion v2After = PolicyVersion.findById(v2.id);
        assertFalse(v2After.active, "v2 应该被停用");

        // 清理额外创建的 v1
        PolicyVersion.deleteById(v1.id);
    }

    /**
     * Phase 3.8 Task 2: 测试边界场景 - anomalyReproduced=false 时不创建 AUTO_ROLLBACK
     *
     * 验证：
     * 1. 异常未复现时不触发自动回滚
     * 2. 不创建 AUTO_ROLLBACK 动作
     */
    @Test
    @Transactional
    void testEndToEnd_NoRollback_AnomalyNotReproduced() {
        // 1. 创建 v1（历史版本）
        PolicyVersion v1 = new PolicyVersion();
        v1.policyId = testPolicyId;
        v1.version = Instant.now().minusSeconds(3600).toEpochMilli();
        v1.moduleName = "test.module";
        v1.functionName = "testFunction";
        v1.content = "v1 content";
        v1.active = false;
        v1.createdAt = Instant.now().minusSeconds(3600);
        v1.persist();

        // 2. 创建异常报告
        PolicyVersion v2 = PolicyVersion.findById(testVersionId);
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "PERFORMANCE_DEGRADATION";
        anomaly.versionId = v2.id;
        anomaly.policyId = testPolicyId;
        anomaly.severity = "CRITICAL";
        anomaly.status = "PENDING";
        anomaly.description = "Performance issue";
        anomaly.detectedAt = Instant.now();
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        // 3. 创建 workflow
        UUID workflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = workflowId;
        workflow.policyVersionId = v2.id;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        anomaly.sampleWorkflowId = workflowId;
        anomaly.persist();

        // 4. 记录验证结果（anomalyReproduced=false 不触发回滚）
        VerificationResult result = new VerificationResult(
            true,  // replaySucceeded
            false, // anomalyReproduced ← 不触发回滚
            workflowId.toString(),
            Instant.now(),
            1000L, 1000L
        );

        Boolean success = workflowService.recordVerificationResult(anomaly.id, result)
            .await().indefinitely();

        assertTrue(success, "记录验证结果应成功");

        // 5. 验证不创建 AUTO_ROLLBACK 动作
        List<AnomalyActionEntity> actions = AnomalyActionEntity
            .find("anomalyId = ?1 and actionType = 'AUTO_ROLLBACK'", anomaly.id)
            .list();

        assertEquals(0, actions.size(), "anomalyReproduced=false 时不应创建 AUTO_ROLLBACK");

        // 清理
        PolicyVersion.deleteById(v1.id);
    }

    /**
     * Phase 3.8 Task 2: 测试边界场景 - 无历史版本时跳过
     *
     * 验证：
     * 1. 只有单一版本时无法回滚
     * 2. 不创建 AUTO_ROLLBACK 动作
     * 3. 记录警告日志
     */
    @Test
    @Transactional
    void testEndToEnd_NoRollback_NoHistoricalVersion() {
        // 1. 只使用 setUp() 创建的版本（无历史版本）
        PolicyVersion v1 = PolicyVersion.findById(testVersionId);

        // 2. 创建异常报告
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "PERFORMANCE_DEGRADATION";
        anomaly.versionId = v1.id;
        anomaly.policyId = testPolicyId;
        anomaly.severity = "CRITICAL";
        anomaly.status = "PENDING";
        anomaly.description = "Performance issue";
        anomaly.detectedAt = Instant.now();
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        // 3. 创建 workflow
        UUID workflowId = UUID.randomUUID();
        WorkflowStateEntity workflow = new WorkflowStateEntity();
        workflow.workflowId = workflowId;
        workflow.policyVersionId = v1.id;
        workflow.status = "COMPLETED";
        workflow.clockTimes = "[1000, 2000, 3000]";
        workflow.startedAt = Instant.now();
        workflow.durationMs = 1000L;
        workflow.persist();

        anomaly.sampleWorkflowId = workflowId;
        anomaly.persist();

        // 4. 记录验证结果（anomalyReproduced=true 但无历史版本）
        VerificationResult result = new VerificationResult(
            true, // replaySucceeded
            true, // anomalyReproduced ← 触发回滚，但无历史版本
            workflowId.toString(),
            Instant.now(),
            1000L, 1200L
        );

        Boolean success = workflowService.recordVerificationResult(anomaly.id, result)
            .await().indefinitely();

        assertTrue(success, "记录验证结果应成功");

        // 5. 验证不创建 AUTO_ROLLBACK 动作（因为无历史版本）
        List<AnomalyActionEntity> actions = AnomalyActionEntity
            .find("anomalyId = ?1 and actionType = 'AUTO_ROLLBACK'", anomaly.id)
            .list();

        assertEquals(0, actions.size(), "无历史版本时不应创建 AUTO_ROLLBACK");
    }

    /**
     * Phase 3.8 Task 2: 测试边界场景 - 幂等性验证（InboxGuard）
     *
     * 验证：
     * 1. 重复执行同一 AUTO_ROLLBACK 动作时被 InboxGuard 拦截
     * 2. 第二次执行返回 true 但跳过实际回滚逻辑
     * 3. 版本状态保持不变
     */
    @Test
    @Transactional
    void testEndToEnd_Idempotency_DuplicateRollback() {
        // 1. 创建 v1（历史版本）
        PolicyVersion v1 = new PolicyVersion();
        v1.policyId = testPolicyId;
        v1.version = Instant.now().minusSeconds(3600).toEpochMilli();
        v1.moduleName = "test.module";
        v1.functionName = "testFunction";
        v1.content = "v1 content";
        v1.active = false;
        v1.createdAt = Instant.now().minusSeconds(3600);
        v1.persist();

        // 2. 创建 v2（当前活跃版本）
        PolicyVersion v2 = PolicyVersion.findById(testVersionId);

        // 3. 创建异常报告
        AnomalyReportEntity anomaly = new AnomalyReportEntity();
        anomaly.anomalyType = "PERFORMANCE_DEGRADATION";
        anomaly.versionId = v2.id;
        anomaly.policyId = testPolicyId;
        anomaly.severity = "CRITICAL";
        anomaly.status = "PENDING";
        anomaly.description = "Performance issue";
        anomaly.detectedAt = Instant.now();
        anomaly.tenantId = "test-tenant";
        anomaly.persist();

        // 4. 手动创建 AUTO_ROLLBACK 动作（模拟已触发场景）
        AnomalyActionEntity action = new AnomalyActionEntity();
        action.anomalyId = anomaly.id;
        action.actionType = "AUTO_ROLLBACK";
        action.status = OutboxStatus.PENDING;
        action.payload = "{\"targetVersion\":" + v1.version + "}";
        action.tenantId = "ANOMALY-" + anomaly.id;
        action.createdAt = Instant.now();
        action.persist();

        // 5. 第一次执行回滚
        Boolean first = actionExecutor.executeAutoRollback(action)
            .await().indefinitely();
        assertTrue(first, "第一次执行应成功");

        // 6. 验证版本切换
        PolicyVersion activeVersion = PolicyVersion.findActiveVersion(testPolicyId);
        assertNotNull(activeVersion);
        assertEquals(v1.version, activeVersion.version, "应该回滚到 v1");

        // 7. 第二次执行同一动作（应被 InboxGuard 拦截）
        Boolean second = actionExecutor.executeAutoRollback(action)
            .await().indefinitely();
        assertTrue(second, "InboxGuard 应返回 true（跳过执行）");

        // 8. 验证版本保持不变
        activeVersion = PolicyVersion.findActiveVersion(testPolicyId);
        assertEquals(v1.version, activeVersion.version, "版本应保持为 v1");

        // 清理
        PolicyVersion.deleteById(v1.id);
    }

}
