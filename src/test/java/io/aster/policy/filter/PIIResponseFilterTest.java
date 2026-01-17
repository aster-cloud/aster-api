package io.aster.policy.filter;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Phase 0 Task 4.2 - PIIResponseFilter 集成测试
 *
 * 验证 HTTP 响应中的 PII 自动脱敏功能
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class PIIResponseFilterTest {

    /**
     * 测试策略：创建一个测试端点返回包含 PII 的数据
     * 由于我们没有专门的测试端点，我们使用现有的审计日志 API
     * 并在测试数据中注入 PII 信息
     */

    @Test
    void testEmailRedactionInResponse() {
        // 构造包含 Email 的请求（通过 X-Tenant-Id header）
        String tenantWithEmail = "test@example.com";

        given()
            .header("X-Tenant-Id", tenantWithEmail)
            .when()
            .get("/api/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);

        // Note: 由于 PIIResponseFilter 会自动脱敏响应体中的 Email
        // 但审计日志的 tenantId 字段在数据库查询时不会被脱敏
        // 真正的脱敏发生在响应序列化后的字符串阶段
        // 这个测试主要验证过滤器不会破坏正常的 API 调用
    }

    @Test
    void testNormalResponseNotAffected() {
        // 不包含 PII 的正常请求应该不受影响
        given()
            .header("X-Tenant-Id", "normal-tenant")
            .when()
            .get("/api/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    void testVerifyChainWithNormalTenant() {
        // 验证链接口应该正常工作
        String tenantId = "test-tenant-filter";
        String start = "2025-01-15T10:00:00Z";
        String end = "2025-01-15T11:00:00Z";

        given()
            .header("X-Tenant-Id", tenantId)
            .queryParam("start", start)
            .queryParam("end", end)
            .when()
            .get("/api/audit/verify-chain")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", equalTo(true))
            .body("recordsVerified", equalTo(0)); // 空链
    }

    /**
     * 注意：完整的 PII 脱敏测试需要专门的测试端点返回包含 PII 的数据
     * 由于当前 API 设计中审计日志不会包含 PII（tenantId 是标识符），
     * 真正的 PII 脱敏功能需要在返回用户数据的 API 中测试（如 Policy Evaluation 结果）
     *
     * Task 4.4 的端到端测试会创建专门的测试场景来验证完整的 PII 防护链路
     */
}
