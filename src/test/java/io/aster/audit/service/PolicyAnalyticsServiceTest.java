package io.aster.audit.service;

import io.aster.audit.dto.AnomalyReportDTO;
import io.aster.policy.entity.PolicyVersion;
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
 * PolicyAnalyticsService 单元测试（Phase 3.8）
 *
 * 验证异常检测功能，特别是 Phase 3.8 新增的 sampleWorkflowId 捕获逻辑：
 * - 高失败率检测能正确捕获代表性失败 workflow
 * - 无失败记录时 sampleWorkflowId 为 null
 */
@QuarkusTest
class PolicyAnalyticsServiceTest {

    @Inject
    PolicyAnalyticsService service;

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
        // 清理测试数据
        WorkflowStateEntity.delete("policyVersionId = ?1", testVersionId);
        PolicyVersion.deleteById(testVersionId);
    }

    /**
     * 测试高失败率检测 - 有失败记录的情况
     *
     * 验证：
     * 1. 检测出高失败率异常
     * 2. sampleWorkflowId 被正确捕获（最近的失败 workflow）
     * 3. 严重程度正确计算（50% 失败率 = CRITICAL）
     */
    @Test
    @Transactional
    void testDetectAnomalies_HighFailureRate_WithSampleWorkflowId() {
        // 准备测试数据：10 个 workflow，5 个成功，5 个失败
        Instant now = Instant.now();
        UUID latestFailedWorkflowId = null;

        for (int i = 0; i < 10; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.HOURS);  // 倒序时间
            workflow.status = (i % 2 == 0) ? "COMPLETED" : "FAILED";
            workflow.durationMs = 1000L;
            workflow.persist();

            // 记录最新的失败 workflow（i=1 是最近的失败）
            if ("FAILED".equals(workflow.status) && latestFailedWorkflowId == null) {
                latestFailedWorkflowId = workflow.workflowId;
            }
        }

        // 执行异常检测（阈值 0.3 = 30%）
        List<AnomalyReportDTO> anomalies = service.detectAnomalies(0.3, 30);

        // 验证检测到高失败率异常
        assertFalse(anomalies.isEmpty(), "应该检测到至少一个异常");

        AnomalyReportDTO anomaly = anomalies.stream()
            .filter(a -> "HIGH_FAILURE_RATE".equals(a.anomalyType))
            .filter(a -> testPolicyId.equals(a.policyId))
            .findFirst()
            .orElse(null);

        assertNotNull(anomaly, "应该检测到 HIGH_FAILURE_RATE 异常");
        assertEquals(testVersionId, anomaly.versionId);
        assertEquals(testPolicyId, anomaly.policyId);
        assertEquals(0.5, anomaly.metricValue, 0.01, "失败率应为 50%");
        assertEquals("CRITICAL", anomaly.severity, "50% 失败率应为 CRITICAL");

        // Phase 3.8: 验证 sampleWorkflowId 被正确捕获
        assertNotNull(anomaly.sampleWorkflowId, "应该捕获代表性失败 workflow ID");
        assertEquals(latestFailedWorkflowId, anomaly.sampleWorkflowId,
            "sampleWorkflowId 应该是最近的失败 workflow");
    }

    /**
     * 测试高失败率检测 - 无失败记录的情况
     *
     * 验证：
     * 1. 低失败率不应触发异常检测
     * 2. 即使有 workflow 记录，失败率低于阈值时不报告异常
     */
    @Test
    @Transactional
    void testDetectAnomalies_LowFailureRate_NoAnomalyDetected() {
        // 准备测试数据：10 个 workflow，全部成功
        Instant now = Instant.now();

        for (int i = 0; i < 10; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.HOURS);
            workflow.status = "COMPLETED";  // 全部成功
            workflow.durationMs = 1000L;
            workflow.persist();
        }

        // 执行异常检测（阈值 0.1 = 10%）
        List<AnomalyReportDTO> anomalies = service.detectAnomalies(0.1, 30);

        // 验证不应检测到此 policy 的异常
        boolean hasAnomaly = anomalies.stream()
            .anyMatch(a -> testPolicyId.equals(a.policyId));

        assertFalse(hasAnomaly, "低失败率不应触发异常检测");
    }

    /**
     * 测试异常检测 - 空数据集
     *
     * 验证：
     * 1. 没有 workflow 记录时不应报告异常
     * 2. 方法不应抛出异常
     */
    @Test
    void testDetectAnomalies_EmptyDataset_NoException() {
        // 不准备任何 workflow 数据

        // 执行异常检测应该成功（不抛出异常）
        assertDoesNotThrow(() -> {
            List<AnomalyReportDTO> anomalies = service.detectAnomalies(0.1, 30);
            assertNotNull(anomalies, "应返回空列表而非 null");
        });
    }

    /**
     * 测试严重程度计算逻辑
     *
     * 验证：
     * - 失败率 >= 50% → CRITICAL
     * - 失败率 >= 30% → WARNING
     * - 失败率 < 30% → INFO
     */
    @Test
    @Transactional
    void testDetectAnomalies_SeverityCalculation() {
        // 准备测试数据：10 个 workflow，4 个失败（40%）
        Instant now = Instant.now();

        for (int i = 0; i < 10; i++) {
            WorkflowStateEntity workflow = new WorkflowStateEntity();
            workflow.workflowId = UUID.randomUUID();
            workflow.policyVersionId = testVersionId;
            workflow.startedAt = now.minus(i, ChronoUnit.HOURS);
            workflow.status = (i < 4) ? "FAILED" : "COMPLETED";  // 前 4 个失败
            workflow.durationMs = 1000L;
            workflow.persist();
        }

        // 执行异常检测（阈值 0.1 = 10%）
        List<AnomalyReportDTO> anomalies = service.detectAnomalies(0.1, 30);

        // 查找此 policy 的异常
        AnomalyReportDTO anomaly = anomalies.stream()
            .filter(a -> "HIGH_FAILURE_RATE".equals(a.anomalyType))
            .filter(a -> testPolicyId.equals(a.policyId))
            .findFirst()
            .orElse(null);

        assertNotNull(anomaly, "应该检测到异常");
        assertEquals(0.4, anomaly.metricValue, 0.01, "失败率应为 40%");
        assertEquals("WARNING", anomaly.severity, "40% 失败率应为 WARNING");
    }

    /**
     * 测试 sampleWorkflowId 捕获逻辑 - 多个失败 workflow
     *
     * 验证：
     * 1. 从多个失败 workflow 中选择最近的一个
     * 2. 使用 ORDER BY started_at DESC LIMIT 1 逻辑
     */
    @Test
    @Transactional
    void testDetectAnomalies_SampleWorkflowId_SelectsMostRecent() {
        // 准备测试数据：创建多个失败 workflow，时间戳不同
        Instant now = Instant.now();
        UUID mostRecentFailedId = UUID.randomUUID();

        // 第一个：最旧的失败
        WorkflowStateEntity oldestFailed = new WorkflowStateEntity();
        oldestFailed.workflowId = UUID.randomUUID();
        oldestFailed.policyVersionId = testVersionId;
        oldestFailed.startedAt = now.minus(10, ChronoUnit.HOURS);
        oldestFailed.status = "FAILED";
        oldestFailed.durationMs = 1000L;
        oldestFailed.persist();

        // 第二个：中间的失败
        WorkflowStateEntity middleFailed = new WorkflowStateEntity();
        middleFailed.workflowId = UUID.randomUUID();
        middleFailed.policyVersionId = testVersionId;
        middleFailed.startedAt = now.minus(5, ChronoUnit.HOURS);
        middleFailed.status = "FAILED";
        middleFailed.durationMs = 1000L;
        middleFailed.persist();

        // 第三个：最新的失败（应该被选中）
        WorkflowStateEntity mostRecentFailed = new WorkflowStateEntity();
        mostRecentFailed.workflowId = mostRecentFailedId;
        mostRecentFailed.policyVersionId = testVersionId;
        mostRecentFailed.startedAt = now.minus(1, ChronoUnit.HOURS);
        mostRecentFailed.status = "FAILED";
        mostRecentFailed.durationMs = 1000L;
        mostRecentFailed.persist();

        // 添加一些成功的 workflow 以达到阈值
        for (int i = 0; i < 2; i++) {
            WorkflowStateEntity success = new WorkflowStateEntity();
            success.workflowId = UUID.randomUUID();
            success.policyVersionId = testVersionId;
            success.startedAt = now.minus(i, ChronoUnit.HOURS);
            success.status = "COMPLETED";
            success.durationMs = 1000L;
            success.persist();
        }

        // 执行异常检测
        List<AnomalyReportDTO> anomalies = service.detectAnomalies(0.3, 30);

        // 验证 sampleWorkflowId 是最新的失败 workflow
        AnomalyReportDTO anomaly = anomalies.stream()
            .filter(a -> "HIGH_FAILURE_RATE".equals(a.anomalyType))
            .filter(a -> testPolicyId.equals(a.policyId))
            .findFirst()
            .orElse(null);

        assertNotNull(anomaly);
        assertNotNull(anomaly.sampleWorkflowId);
        assertEquals(mostRecentFailedId, anomaly.sampleWorkflowId,
            "应该选择最近的失败 workflow 作为 sample");
    }
}
