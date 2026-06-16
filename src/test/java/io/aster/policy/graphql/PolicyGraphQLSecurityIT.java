package io.aster.policy.graphql;

import io.aster.security.apikey.ApiKeyVerifierService;
import io.aster.security.apikey.ApiKeyVerifyResult;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * #55: 端到端验证 {@code /graphql} 的 API-key 鉴权边界 + 身份租户解析 + 角色门控。
 *
 * <p>这是该安全修复的负向回归套件，覆盖 issue 要求的三条断言：
 * <ol>
 *   <li>匿名（无 API key）的 mutation 必须被拒（401）；</li>
 *   <li>带伪造 {@code X-Tenant-Id} 但持有效 key 的请求，必须作用于**已验证租户**，
 *       而非 header 租户（跨租户隔离）；</li>
 *   <li>非特权角色（MEMBER）不得执行需 ADMIN 的 mutation（clearAllCache）。</li>
 * </ol>
 *
 * <p>沿用 {@link io.aster.security.apikey.ApiKeyTenantIsolationIT} 的做法：打开 apikey
 * + rbac，关 signature（贴近生产），通过 {@link ApiKeyVerifierService#seedCacheForTest}
 * 预置有效 key 模拟 cloud verify。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PolicyGraphQLSecurityIT.GraphQLSecurityProfile.class)
class PolicyGraphQLSecurityIT {

    public static class GraphQLSecurityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.security.apikey.enabled", "true",
                "aster.security.signature.enabled", "false",
                "aster.security.rbac.enabled", "true"
            );
        }
    }

    private static final String TENANT_A = "tenant-a";
    private static final String MEMBER_KEY = "ak_test_gql_member_a";
    private static final String ADMIN_KEY = "ak_test_gql_admin_a";

    @Inject
    ApiKeyVerifierService verifier;

    @BeforeEach
    void seedKeys() {
        verifier.seedCacheForTest(MEMBER_KEY,
            ApiKeyVerifyResult.valid("ak-gql-1", "user-m", TENANT_A, "pro", "active", "MEMBER"));
        verifier.seedCacheForTest(ADMIN_KEY,
            ApiKeyVerifyResult.valid("ak-gql-2", "user-a", TENANT_A, "pro", "active", "ADMIN"));
    }

    private Map<String, String> body(String query) {
        return Map.of("query", query);
    }

    private String createPolicyMutation() {
        return """
            mutation {
              createPolicy(input: {
                name: "SecTest-%s"
                allow: { rules: [{ resourceType: "loan", patterns: ["READ"] }] }
                deny: { rules: [] }
              }) {
                id
                name
              }
            }
            """.formatted(UUID.randomUUID());
    }

    @Test
    void anonymousMutation_isRejectedWith401() {
        // 无 Authorization → GraphQLApiKeyAuthHandler 在引擎前 401，绝不到达 resolver。
        given()
            .contentType("application/json")
            .body(body(createPolicyMutation()))
        .when()
            .post("/graphql")
        .then()
            .statusCode(401);
    }

    @Test
    void bogusKeyMutation_isRejectedWith401() {
        given()
            .header("Authorization", "Bearer ak_does_not_exist")
            .contentType("application/json")
            .body(body(createPolicyMutation()))
        .when()
            .post("/graphql")
        .then()
            .statusCode(401);
    }

    @Test
    void spoofedTenantHeader_operatesOnAuthenticatedTenant_not_header() {
        // 持 tenant-a 的有效 key 带 X-Tenant-Id: victim-tenant。
        // 跨租户冲突 → 边界 403 tenant_mismatch（与 REST 侧一致），绝不落到 victim。
        given()
            .header("Authorization", "Bearer " + MEMBER_KEY)
            .header("X-Tenant-Id", "victim-tenant")
            .contentType("application/json")
            .body(body(createPolicyMutation()))
        .when()
            .post("/graphql")
        .then()
            .statusCode(403);
    }

    @Test
    void validKeyNoTenantHeader_createsUnderAuthenticatedTenant() {
        // 不带 X-Tenant-Id：租户应解析为已验证租户 tenant-a，create 成功。
        io.restassured.response.Response resp = given()
            .header("Authorization", "Bearer " + MEMBER_KEY)
            .contentType("application/json")
            .body(body(createPolicyMutation()))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.createPolicy.id", notNullValue())
            .extract().response();
        Assertions.assertNotNull(resp.path("data.createPolicy.id"),
            "已验证租户下 createPolicy 应返回 id");
    }

    @Test
    void memberRole_cannotClearAllCache_requiresAdmin() {
        // clearAllCache 要求 ADMIN；持 MEMBER key（且角色来自已验证身份，不可伪造）→ 被拒。
        String mutation = """
            mutation {
              clearAllCache { success message timestamp }
            }
            """;
        given()
            .header("Authorization", "Bearer " + MEMBER_KEY)
            // 伪造 X-User-Role: ADMIN —— 必须无效（角色来自已验证 key，非 header）。
            .header("X-User-Role", "ADMIN")
            .contentType("application/json")
            .body(body(mutation))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            // GraphQL 把授权失败表达为 errors（不是 HTTP 403），data.clearAllCache 应为 null。
            .body("errors", notNullValue());
    }

    @Test
    void adminRole_canClearAllCache() {
        String mutation = """
            mutation {
              clearAllCache { success message timestamp }
            }
            """;
        given()
            .header("Authorization", "Bearer " + ADMIN_KEY)
            .contentType("application/json")
            .body(body(mutation))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.clearAllCache.success", notNullValue());
    }
}
