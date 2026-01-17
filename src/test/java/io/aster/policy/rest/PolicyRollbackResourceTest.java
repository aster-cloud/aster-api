package io.aster.policy.rest;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.EventType;
import io.aster.policy.rest.model.RollbackRequest;
import io.aster.policy.service.PolicyVersionService;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Policy Rollback API 集成测试（Phase 3.5）
 *
 * 测试 POST /api/policies/{policyId}/rollback 端点：
 * - 成功回滚场景
 * - 版本不存在错误处理
 * - 参数验证失败
 * - 审计日志完整性验证
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
public class PolicyRollbackResourceTest {

    @Inject
    PolicyVersionService policyVersionService;

    private String testPolicyId = "aster.test.rollback";
    private PolicyVersion v1, v2, v3;

    @BeforeEach
    @Transactional
    void setUp() {
        // 清理旧数据
        PolicyVersion.delete("policyId", testPolicyId);
        AuditLog.delete("policyId", testPolicyId);

        // 创建 3 个测试版本
        v1 = policyVersionService.createVersion(
            testPolicyId,
            "aster.test",
            "rollback",
            "function rollback() { return 'v1'; }",
            "test-user",
            "Version 1"
        );
        v2 = policyVersionService.createVersion(
            testPolicyId,
            "aster.test",
            "rollback",
            "function rollback() { return 'v2'; }",
            "test-user",
            "Version 2"
        );
        v3 = policyVersionService.createVersion(
            testPolicyId,
            "aster.test",
            "rollback",
            "function rollback() { return 'v3'; }",
            "test-user",
            "Version 3"
        );
    }

    // ==================== 成功回滚测试 ====================

    /**
     * 测试成功回滚到历史版本
     */
    @Test
    void testRollbackSuccess() {
        RollbackRequest request = new RollbackRequest(v2.version, "Test rollback to v2");

        given()
            .contentType(APPLICATION_JSON)
            .body(request)
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user")
        .when()
            .post("/api/policies/" + testPolicyId + "/rollback")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("activeVersion", is(v2.version))
            .body("message", containsString("成功回滚"));

        // 验证数据库状态
        PolicyVersion active = policyVersionService.getActiveVersion(testPolicyId);
        assertEquals(v2.version, active.version);
        assertTrue(active.active);
    }

    /**
     * 测试 reason 为 null 的回滚功能
     */
    @Test
    void testRollbackWithNullReason() throws InterruptedException {
        RollbackRequest request = new RollbackRequest(v1.version, null);

        given()
            .contentType(APPLICATION_JSON)
            .body(request)
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user")
        .when()
            .post("/api/policies/" + testPolicyId + "/rollback")
        .then()
            .statusCode(200)
            .body("success", is(true));

        // 等待审计日志异步写入
        waitForAuditLog();

        // 验证审计日志的 reason 字段为 null
        AuditLog log = AuditLog.find("eventType = ?1 AND policyId = ?2 ORDER BY timestamp DESC",
            "POLICY_ROLLBACK", testPolicyId).firstResult();
        assertNotNull(log);
        assertNull(log.reason);
    }

    /**
     * 测试回滚到不存在版本的错误处理
     */
    @Test
    void testRollbackNonExistentVersion() {
        RollbackRequest request = new RollbackRequest(999L, "Invalid rollback");

        given()
            .contentType(APPLICATION_JSON)
            .body(request)
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user")
        .when()
            .post("/api/policies/" + testPolicyId + "/rollback")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("message", containsString("版本不存在"))
            .body("activeVersion", nullValue());
    }

    // ==================== 参数验证失败测试 ====================

    /**
     * 测试缺少必填参数 targetVersion（Bean Validation 失败）
     */
    @Test
    void testRollbackMissingTargetVersion() {
        String invalidRequest = "{\"reason\": \"Missing version\"}";

        given()
            .contentType(APPLICATION_JSON)
            .body(invalidRequest)
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user")
        .when()
            .post("/api/policies/" + testPolicyId + "/rollback")
        .then()
            .statusCode(400);
    }

    // ==================== 审计日志验证测试 ====================

    /**
     * 测试审计日志完整性（验证所有字段正确记录）
     */
    @Test
    void testRollbackAuditLogDetails() throws InterruptedException {
        RollbackRequest request = new RollbackRequest(v1.version, "Rollback to initial version");

        given()
            .contentType(APPLICATION_JSON)
            .body(request)
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user")
        .when()
            .post("/api/policies/" + testPolicyId + "/rollback");

        // 等待审计日志异步写入
        waitForAuditLog();

        // 查询审计日志（最新的 POLICY_ROLLBACK 事件）
        AuditLog log = AuditLog.find("eventType = ?1 AND policyId = ?2 ORDER BY timestamp DESC",
            "POLICY_ROLLBACK", testPolicyId).firstResult();

        assertNotNull(log, "Audit log should be created");
        assertEquals("POLICY_ROLLBACK", log.eventType);
        assertEquals(testPolicyId, log.policyId);
        assertEquals(v3.version, log.fromVersion, "Should record rollback from v3");
        assertEquals(v1.version, log.toVersion, "Should record rollback to v1");
        assertEquals("test-user", log.performedBy);
        assertEquals("test-tenant", log.tenantId);
        assertTrue(log.success);
        assertEquals("Rollback to initial version", log.reason);
    }

    /**
     * 轮询等待审计日志写入（最多 5 秒）
     */
    private void waitForAuditLog() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            long count = AuditLog.count("eventType = ?1 AND policyId = ?2",
                "POLICY_ROLLBACK", testPolicyId);
            if (count > 0) return;
            Thread.sleep(500);
        }
    }
}
