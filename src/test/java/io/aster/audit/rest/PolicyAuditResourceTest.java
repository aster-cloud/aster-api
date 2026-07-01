package io.aster.audit.rest;

import aster.runtime.workflow.WorkflowMetadata;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.aster.workflow.PostgresEventStore;
import io.aster.workflow.PostgresWorkflowRuntime;
import io.aster.workflow.WorkflowStateEntity;
import io.quarkus.test.junit.QuarkusTest;
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
 * 策略审计 API 集成测试（Phase 3.2）
 *
 * 测试 6 个审计查询 API 端点的功能完整性、参数验证和错误处理。
 */
@QuarkusTest
public class PolicyAuditResourceTest {

    private static final String TEST_TENANT_ID = "test-tenant";

    @Inject
    PolicyVersionService policyVersionService;

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    PostgresEventStore eventStore;

    private String testPolicyId;
    private PolicyVersion testVersion;
    private String workflowId1;
    private String workflowId2;

    @BeforeEach
    @Transactional
    void setUp() {
        // 创建测试策略版本
        testPolicyId = "aster.test.auditPolicy";
        testVersion = policyVersionService.createVersion(
            testPolicyId,
            "aster.test",
            "auditPolicy",
            "function auditPolicy() { return 'test'; }",
            "test-user",
            "Test policy for Phase 3.2"
        );
        // 租户归属：审计查询按 X-Tenant-Id 校验版本归属（红队 P0-A），
        // 测试版本必须携带与请求头一致的租户，正向用例才可通过。
        testVersion.tenantId = TEST_TENANT_ID;
        testVersion.runtimeBuild = "test-build-1.0.0";
        testVersion.artifactSha256 = ARTIFACT_SHA;
        testVersion.persist();

        // 创建测试 workflow
        workflowId1 = UUID.randomUUID().toString();
        workflowId2 = UUID.randomUUID().toString();

        WorkflowMetadata metadata = new WorkflowMetadata();
        metadata.set(WorkflowMetadata.Keys.POLICY_ID, testPolicyId);

        workflowRuntime.schedule(workflowId1, null, metadata);
        workflowRuntime.schedule(workflowId2, null, metadata);

        // 设置 policyVersionId 以便 getVersionUsage 可以查询到
        WorkflowStateEntity state1 = WorkflowStateEntity.find("workflowId = ?1", UUID.fromString(workflowId1)).firstResult();
        WorkflowStateEntity state2 = WorkflowStateEntity.find("workflowId = ?1", UUID.fromString(workflowId2)).firstResult();
        if (state1 != null) {
            state1.policyVersionId = testVersion.id;
            state1.policyActivatedAt = Instant.now();
            state1.tenantId = TEST_TENANT_ID;
            state1.persist();
        }
        if (state2 != null) {
            state2.policyVersionId = testVersion.id;
            state2.policyActivatedAt = Instant.now();
            state2.tenantId = TEST_TENANT_ID;
            state2.persist();
        }

        // 记录事件
        eventStore.appendEvent(workflowId1, "WorkflowStarted", "{}");
        eventStore.appendEvent(workflowId2, "WorkflowStarted", "{}");
    }

    /**
     * 测试版本使用查询 - 默认分页参数
     */
    @Test
    void testGetVersionUsage_DefaultPagination() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/policy-versions/{versionId}/usage", testVersion.id)
            .then()
            .statusCode(200)
            .body("items.size()", greaterThan(0))
            .body("total", greaterThan(0))
            .body("page", is(0))
            .body("size", is(20))
            .body("hasMore", notNullValue());
    }

    /**
     * 测试版本使用查询 - 带状态过滤
     */
    @Test
    void testGetVersionUsage_WithStatusFilter() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("status", "READY")
            .when().get("/api/v1/audit/policy-versions/{versionId}/usage", testVersion.id)
            .then()
            .statusCode(200)
            .body("items", notNullValue());
    }

    /**
     * 测试版本使用查询 - 无效分页参数（负数页码）
     */
    @Test
    void testGetVersionUsage_InvalidPagination_NegativePage() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("page", -1)
            .when().get("/api/v1/audit/policy-versions/{versionId}/usage", testVersion.id)
            .then()
            .statusCode(400);
    }

    /**
     * 测试版本使用查询 - 无效分页参数（size 超过最大值）
     */
    @Test
    void testGetVersionUsage_InvalidPagination_SizeExceedsMax() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("size", 101)
            .when().get("/api/v1/audit/policy-versions/{versionId}/usage", testVersion.id)
            .then()
            .statusCode(400);
    }

    /**
     * 测试时间线查询 - 带时间范围和分页
     */
    @Test
    void testGetVersionTimeline_WithTimeRangeAndPagination() {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("from", from.toString())
            .queryParam("to", to.toString())
            .queryParam("page", 0)
            .queryParam("size", 10)
            .when().get("/api/v1/audit/policy-versions/{versionId}/timeline", testVersion.id)
            .then()
            .statusCode(200)
            .body("items", notNullValue())
            .body("page", is(0))
            .body("size", is(10));
    }

    /**
     * 测试时间线查询 - 缺少必填参数
     */
    @Test
    void testGetVersionTimeline_MissingRequiredParams() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("page", 0)
            .queryParam("size", 10)
            .when().get("/api/v1/audit/policy-versions/{versionId}/timeline", testVersion.id)
            .then()
            .statusCode(400);
    }

    /**
     * 测试时间线查询 - from 晚于 to
     */
    @Test
    void testGetVersionTimeline_InvalidTimeRange() {
        Instant from = Instant.now();
        Instant to = from.minus(1, ChronoUnit.HOURS);

        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .queryParam("from", from.toString())
            .queryParam("to", to.toString())
            .queryParam("page", 0)
            .queryParam("size", 10)
            .when().get("/api/v1/audit/policy-versions/{versionId}/timeline", testVersion.id)
            .then()
            .statusCode(400);
    }

    /**
     * 测试影响评估 - 返回正确的统计数据
     */
    @Test
    void testAssessImpact_ReturnsCorrectCounts() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/policy-versions/{versionId}/impact", testVersion.id)
            .then()
            .statusCode(200)
            .body("versionId", is(testVersion.id.intValue()))
            .body("totalCount", greaterThan(0))
            .body("riskLevel", notNullValue());
    }

    /**
     * 测试影响评估 - 版本不存在（返回 404）
     */
    @Test
    void testAssessImpact_NotFound() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/policy-versions/{versionId}/impact", 999999L)
            .then()
            .statusCode(404);
    }

    /**
     * 测试 workflow 版本历史查询
     */
    @Test
    void testGetWorkflowVersionHistory() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/workflows/{workflowId}/version-history", workflowId1)
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    /**
     * 测试 workflow 版本历史查询 - 无效 UUID 格式
     */
    @Test
    void testGetWorkflowVersionHistory_InvalidUUID() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/workflows/{workflowId}/version-history", "invalid-uuid")
            .then()
            .statusCode(400);
    }

    /**
     * 测试编译产物查询 - 不存在的 SHA256（返回 404）
     */
    @Test
    void testGetArtifact_NotFound() {
        String validSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/artifacts/{sha256}", validSha256)
            .then()
            .statusCode(404);
    }

    /**
     * 测试编译产物查询 - 无效 SHA256 格式（返回 400）
     */
    @Test
    void testGetArtifact_InvalidFormat() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/artifacts/{sha256}", "invalid-sha256")
            .then()
            .statusCode(400);
    }

    /**
     * 测试 runtime 策略查询
     */
    @Test
    void testGetRuntimePolicies() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/runtime/{build}/policies", "test-build-1.0.0")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    /**
     * 测试 runtime 策略查询 - 空参数（返回 400）
     */
    @Test
    void testGetRuntimePolicies_EmptyBuild() {
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/runtime/{build}/policies", " ")
            .then()
            .statusCode(400);
    }

    // ── 跨租户隔离回归（红队 P0-A：审计 IDOR）──────────────────────────────────
    // 另一租户猜到本租户的 versionId / workflowId / sha256 / runtimeBuild，
    // 必须读不到任何数据（空结果或 404），不得泄露存在性或计数。

    private static final String OTHER_TENANT_ID = "attacker-tenant";
    // 64 位 hex，符合资源层 SHA256 格式校验（^[a-fA-F0-9]{64}$）。
    private static final String ARTIFACT_SHA =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void testCrossTenant_Usage_ReturnsEmpty() {
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .when().get("/api/v1/audit/policy-versions/{versionId}/usage", testVersion.id)
            .then()
            .statusCode(200)
            .body("items.size()", is(0))
            .body("total", is(0));
    }

    @Test
    void testCrossTenant_Timeline_ReturnsEmpty() {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .queryParam("from", from.toString())
            .queryParam("to", to.toString())
            .when().get("/api/v1/audit/policy-versions/{versionId}/timeline", testVersion.id)
            .then()
            .statusCode(200)
            .body("items.size()", is(0))
            .body("total", is(0));
    }

    @Test
    void testCrossTenant_Impact_Returns404() {
        // 跨租户视为「版本不存在」：totalCount=0 → 资源层抛 404，不泄露计数
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .when().get("/api/v1/audit/policy-versions/{versionId}/impact", testVersion.id)
            .then()
            .statusCode(404);
    }

    @Test
    void testCrossTenant_WorkflowVersionHistory_ReturnsEmpty() {
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .when().get("/api/v1/audit/workflows/{workflowId}/version-history", workflowId1)
            .then()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void testCrossTenant_RuntimePolicies_ReturnsEmpty() {
        // testVersion.runtimeBuild = "test-build-1.0.0" 属 TEST_TENANT_ID，
        // attacker-tenant 按同一 build 查应查不到任何策略
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .when().get("/api/v1/audit/runtime/{build}/policies", "test-build-1.0.0")
            .then()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void testSameTenant_RuntimePolicies_ReturnsData() {
        // 同租户正向用例：确认租户过滤没有误杀本租户数据
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/runtime/{build}/policies", "test-build-1.0.0")
            .then()
            .statusCode(200)
            .body("size()", greaterThan(0));
    }

    // 红队 P0-A（Codex 审查后补）：artifact 按 sha256 查询的跨租户隔离。
    @Test
    void testCrossTenant_Artifact_Returns404() {
        // attacker-tenant 猜到本租户产物 sha256，按租户范围查应查不到 → 404（不泄露存在性）。
        given()
            .header("X-Tenant-Id", OTHER_TENANT_ID)
            .when().get("/api/v1/audit/artifacts/{sha256}", ARTIFACT_SHA)
            .then()
            .statusCode(404);
    }

    @Test
    void testSameTenant_Artifact_ReturnsData() {
        // 同租户正向：确认租户过滤没误杀本租户产物。
        given()
            .header("X-Tenant-Id", TEST_TENANT_ID)
            .when().get("/api/v1/audit/artifacts/{sha256}", ARTIFACT_SHA)
            .then()
            .statusCode(200)
            .body("artifactSha256", is(ARTIFACT_SHA));
    }
}
