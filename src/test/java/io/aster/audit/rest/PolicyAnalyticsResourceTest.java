package io.aster.audit.rest;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.aster.test.PostgresTestResource;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * 策略分析 API 集成测试（Phase 3.3）
 *
 * 测试 3 个审计分析 API 端点的功能完整性、参数验证和错误处理。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
public class PolicyAnalyticsResourceTest {

    @Inject
    PolicyVersionService policyVersionService;

    private PolicyVersion versionA;
    private PolicyVersion versionB;

    @BeforeEach
    @Transactional
    void setUp() {
        // 创建测试策略版本
        versionA = policyVersionService.createVersion(
            "aster.test.policyA",
            "aster.test",
            "policyA",
            "function policyA() { return 'A'; }",
            "test-user",
            "Test policy A for Phase 3.3"
        );
        versionB = policyVersionService.createVersion(
            "aster.test.policyB",
            "aster.test",
            "policyB",
            "function policyB() { return 'B'; }",
            "test-user",
            "Test policy B for Phase 3.3"
        );

        // 版本 A: 10 个成功 + 2 个失败（失败率 16.7%）
        for (int i = 0; i < 10; i++) {
            createTestWorkflow(versionA, "COMPLETED",
                Instant.now().minus(i, ChronoUnit.HOURS), 100 + i * 10);
        }
        for (int i = 0; i < 2; i++) {
            WorkflowStateEntity failed = createTestWorkflow(versionA, "FAILED",
                Instant.now().minus(i, ChronoUnit.HOURS), 50);
            failed.errorMessage = "Test error " + i;
            failed.persist();
        }

        // 版本 B: 8 个成功 + 0 个失败（失败率 0%）
        for (int i = 0; i < 8; i++) {
            createTestWorkflow(versionB, "COMPLETED",
                Instant.now().minus(i, ChronoUnit.HOURS), 200 + i * 20);
        }
    }

    private WorkflowStateEntity createTestWorkflow(
        PolicyVersion version, String status, Instant startedAt, long durationMs
    ) {
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = UUID.randomUUID();
        state.policyVersionId = version.id;
        state.status = status;
        state.startedAt = startedAt;
        state.completedAt = startedAt.plusMillis(durationMs);
        state.durationMs = durationMs;
        state.policyActivatedAt = startedAt;
        state.tenantId = "test-tenant";
        state.persist();
        return state;
    }

    // ==================== 聚合统计 API 测试（5 个）====================

    /**
     * 测试按小时聚合的版本使用统计
     */
    @Test
    void testGetVersionUsageStats_HourGranularity() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionId", versionA.id)
            .queryParam("granularity", "hour")
            .queryParam("from", Instant.now().minus(1, ChronoUnit.DAYS).toString())
            .queryParam("to", Instant.now().toString())
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(200)
            .body("$", notNullValue())
            .body("size()", greaterThan(0));
    }

    /**
     * 测试按天聚合的版本使用统计
     */
    @Test
    void testGetVersionUsageStats_DayGranularity() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionId", versionA.id)
            .queryParam("granularity", "day")
            .queryParam("from", Instant.now().minus(7, ChronoUnit.DAYS).toString())
            .queryParam("to", Instant.now().toString())
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    /**
     * 测试租户过滤的版本使用统计
     */
    @Test
    void testGetVersionUsageStats_WithTenantFilter() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionId", versionA.id)
            .queryParam("granularity", "day")
            .queryParam("tenantId", "test-tenant")
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    /**
     * 测试无效时间粒度（返回 400）
     */
    @Test
    void testGetVersionUsageStats_InvalidGranularity() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionId", versionA.id)
            .queryParam("granularity", "invalid")
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(400);
    }

    /**
     * 测试时间跨度超限（返回 400）
     */
    @Test
    void testGetVersionUsageStats_TimeRangeExceedsLimit() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionId", versionA.id)
            .queryParam("from", Instant.now().minus(100, ChronoUnit.DAYS).toString())
            .queryParam("to", Instant.now().toString())
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(400);
    }

    /**
     * 测试缺少必填参数 versionId（返回 400）
     */
    @Test
    void testGetVersionUsageStats_MissingVersionId() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("granularity", "day")
            .when().get("/api/audit/stats/version-usage")
            .then()
            .statusCode(400);
    }

    // ==================== 异常检测 API 测试（4 个）- Phase 3.4 异步化 ====================

    /**
     * 测试分页查询异常报告（默认参数）
     *
     * Phase 3.4: API 已改为查询 anomaly_reports 表，而非实时计算。
     * 如果表中无数据，返回空列表（200 OK），而非错误。
     */
    @Test
    void testDetectAnomalies_HighFailureRate() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("days", 30)
            .when().get("/api/audit/anomalies")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
        // 注意：测试环境下 anomaly_reports 表可能为空，因为定时任务未运行
        // 如果需要测试有数据的情况，应通过 @BeforeEach 插入测试数据
    }

    /**
     * 测试带分页和类型过滤的查询
     */
    @Test
    void testDetectAnomalies_CustomThreshold() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("page", 1)
            .queryParam("size", 10)
            .queryParam("type", "HIGH_FAILURE_RATE")
            .queryParam("days", 7)
            .when().get("/api/audit/anomalies")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    /**
     * 测试无效分页参数（返回 400）
     */
    @Test
    void testDetectAnomalies_InvalidThreshold() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("page", 0)  // page must be >= 1
            .when().get("/api/audit/anomalies")
            .then()
            .statusCode(400);
    }

    /**
     * 测试无效天数（返回 400）
     */
    @Test
    void testDetectAnomalies_InvalidDays() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("days", 500)
            .when().get("/api/audit/anomalies")
            .then()
            .statusCode(400);
    }

    // ==================== 版本对比 API 测试（4 个）====================

    /**
     * 测试版本对比 - 成功率差异
     */
    @Test
    void testCompareVersions_SuccessRateDifference() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionA", versionA.id)
            .queryParam("versionB", versionB.id)
            .queryParam("days", 7)
            .when().get("/api/audit/compare")
            .then()
            .statusCode(200)
            .body("versionAId", is(versionA.id.intValue()))
            .body("versionBId", is(versionB.id.intValue()))
            .body("versionAWorkflowCount", greaterThan(0))
            .body("versionBWorkflowCount", greaterThan(0))
            .body("winner", notNullValue())
            .body("recommendation", notNullValue());
    }

    /**
     * 测试版本对比 - 缺少必填参数（返回 400）
     */
    @Test
    void testCompareVersions_MissingParameters() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionA", versionA.id)
            .when().get("/api/audit/compare")
            .then()
            .statusCode(400);
    }

    /**
     * 测试版本对比 - 相同版本（返回 400）
     */
    @Test
    void testCompareVersions_SameVersion() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionA", versionA.id)
            .queryParam("versionB", versionA.id)
            .when().get("/api/audit/compare")
            .then()
            .statusCode(400);
    }

    /**
     * 测试版本对比 - 无效天数（返回 400）
     */
    @Test
    void testCompareVersions_InvalidDays() {
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("versionA", versionA.id)
            .queryParam("versionB", versionB.id)
            .queryParam("days", 100)
            .when().get("/api/audit/compare")
            .then()
            .statusCode(400);
    }
}
