package io.aster.policy.metrics;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * WAADR 物化视图 + REST 端点集成测试
 *
 * 用 Testcontainers Postgres 跑真实 V6.7 / V6.8 Flyway 迁移，
 * 插入 policy_versions 数据后 REFRESH 物化视图，再调 REST 验证。
 *
 * 视图 SQL 详见 db/migration/V6.8.0__create_pm_weekly_waadr.sql
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WaadrMetricsResourceIT {

    @Inject
    EntityManager em;

    @Inject
    WaadrMetricsService service;

    @BeforeEach
    @Transactional
    void cleanData() {
        em.createNativeQuery("DELETE FROM policy_versions WHERE policy_id LIKE 'it-waadr-%'").executeUpdate();
        // 确保物化视图为空状态（使用 CONCURRENTLY 需要存在数据；NON-CONCURRENT 总能跑）
        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();
    }

    @Test
    @Order(1)
    @Transactional
    void insertAdoptedDraftAndRefresh_yieldsWaadrRow() {
        // 插入 3 条满足 WAADR 条件的版本（同 tenant，同周）
        Instant week = Instant.parse("2026-04-27T00:00:00Z"); // 周一
        for (int i = 0; i < 3; i++) {
            PolicyVersion v = new PolicyVersion(
                "it-waadr-policy-" + i,
                "aster.test",
                "fn" + i,
                "Module aster.test.\nRule fn" + i + " given x: Return x.",
                "tester",
                null
            );
            v.tenantId = "tenant-it";
            v.sourceKind = "ai_draft_edited";
            v.authorRole = "business_expert";
            v.status = VersionStatus.APPROVED;
            v.activatedAt = week.plusSeconds(3600L * (i + 1));
            v.activatedBy = "approver";
            v.approvedBy = "approver";
            v.approvedAt = v.activatedAt;
            v.locale = "zh";
            v.persist();
        }

        // 物化视图刷新
        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();

        // 通过 service 查询验证
        var rows = service.fetchWeeklyWaadr("tenant-it", 12);
        org.junit.jupiter.api.Assertions.assertFalse(rows.isEmpty(), "应至少有一行 WAADR 数据");
        long sum = rows.stream().mapToLong(r -> r.waadr()).sum();
        org.junit.jupiter.api.Assertions.assertTrue(sum >= 3, "三条 ai_draft_edited 应被聚合到至少 3");
    }

    @Test
    @Order(2)
    @Transactional
    void manualVersion_doesNotCountAsWaadr() {
        // manual 版本不应进入 WAADR
        PolicyVersion v = new PolicyVersion(
            "it-waadr-manual",
            "aster.test",
            "manual_fn",
            "Module aster.test.\nRule manual_fn given x: Return x.",
            "tester",
            null
        );
        v.tenantId = "tenant-manual";
        v.sourceKind = "manual";
        v.authorRole = "business_expert"; // 即便业务角色，manual 仍不计 WAADR
        v.status = VersionStatus.APPROVED;
        v.activatedAt = Instant.now();
        v.locale = "zh";
        v.persist();

        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();
        var rows = service.fetchWeeklyWaadr("tenant-manual", 12);
        org.junit.jupiter.api.Assertions.assertTrue(rows.isEmpty(),
            "manual 版本不应被计入 WAADR（仅 ai_draft_edited 计入）");
    }

    @Test
    @Order(3)
    void restEndpoint_returnsWaadrPoints() {
        // 端点带 RBAC：要求 ADMIN 角色 + tenant 头
        given()
            .header("X-Tenant-Id", "tenant-it")
            .header("X-User-Id", "tester-admin")
            .header("X-Role", "ADMIN")
            .queryParam("weeks", "12")
            .when()
            .get("/api/v1/metrics/waadr")
            .then()
            .statusCode(200);
        // body 内容由前面测试的状态决定，这里仅断言端点返回 200
    }
}
