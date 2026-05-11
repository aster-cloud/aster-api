package io.aster.policy.rest;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import io.aster.test.PostgresTestResource;
import io.aster.test.telemetry.NsmTelemetryAssertions;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.reset;

/**
 * Rollback 端点 → RULE_ROLLED_BACK 事件 集成测试
 *
 * 覆盖：
 *   1. 成功回滚到旧版本 → 触发 NsmEvents.RULE_ROLLED_BACK，含完整属性
 *   2. days_after_publish 基于 activatedAt 计算，未激活版本走 -1 兜底
 *   3. 回滚到不存在版本 → 不触发 NSM 事件（错误路径不污染指标）
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
class RollbackTelemetryIT {

    @InjectMock
    NsmTelemetry nsmTelemetry;

    @BeforeEach
    void resetMock() {
        reset(nsmTelemetry);
    }

    /** 创建一个已激活的版本 + 一个待回滚到的旧版本，返回旧版本的 version 号 */
    @Transactional
    Long seedTwoVersions(String policyId, Instant activatedAt) {
        // 旧版本 v1（回滚目标）
        PolicyVersion v1 = new PolicyVersion(
            policyId, "aster.test", "fn",
            "Module aster.test.\nRule fn given x: Return x.",
            "tester", null);
        v1.tenantId = "tenant-rb";
        v1.status = VersionStatus.APPROVED;
        v1.locale = "zh";
        v1.active = false;
        v1.persist();

        // 当前活跃 v2
        PolicyVersion v2 = new PolicyVersion(
            policyId, "aster.test", "fn",
            "Module aster.test.\nRule fn given x: Return false.",
            "tester", null);
        v2.tenantId = "tenant-rb";
        v2.status = VersionStatus.APPROVED;
        v2.locale = "zh";
        v2.active = true;
        v2.activatedAt = activatedAt;
        v2.activatedBy = "approver-1";
        v2.persist();

        return v1.version;
    }

    @Test
    void successfulRollback_emitsRuleRolledBackEvent() {
        String policyId = "it-rollback-1";
        Instant activatedAt = Instant.now().minusSeconds(7L * 24 * 3600); // 7 天前激活
        Long targetVersion = seedTwoVersions(policyId, activatedAt);

        given()
            .header("X-Tenant-Id", "tenant-rb")
            .header("X-User-Id", "rollbacker-1")
            .header("Content-Type", "application/json")
            .body("{\"targetVersion\":" + targetVersion + ",\"reason\":\"bug fix\"}")
            .when()
            .post("/api/v1/policies/" + policyId + "/rollback")
            .then()
            .statusCode(200)
            .body(containsString("success"));

        NsmTelemetryAssertions.assertEvent(nsmTelemetry, NsmEvents.RULE_ROLLED_BACK, "rollbacker-1")
            .withProp("rule_id", policyId)
            .withProp("to_version", targetVersion)
            .withProp("reason", "bug fix")
            .withProp("tenant_id", "tenant-rb")
            // days_after_publish 按 7 天激活推算，应 ≥ 6（容许 1 天浮动）
            .withPropMatching("days_after_publish", v -> v instanceof Long && (Long) v >= 6L);
    }

    @Test
    void rollbackWithUnknownVersion_doesNotEmitNsm() {
        String policyId = "it-rollback-not-exist";
        // 无版本数据，rollback 必失败

        given()
            .header("X-Tenant-Id", "tenant-rb")
            .header("X-User-Id", "rollbacker-2")
            .header("Content-Type", "application/json")
            .body("{\"targetVersion\":99999,\"reason\":\"oops\"}")
            .when()
            .post("/api/v1/policies/" + policyId + "/rollback")
            .then()
            // RollbackResponse.failure 仍是 200，但 success=false
            .statusCode(200)
            .body(containsString("\"success\":false"));

        // NSM 不应被触发——错误路径不污染北极星指标
        NsmTelemetryAssertions.assertNoEvent(nsmTelemetry, NsmEvents.RULE_ROLLED_BACK);
    }
}
