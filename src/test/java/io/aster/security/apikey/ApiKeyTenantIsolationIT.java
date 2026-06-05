package io.aster.security.apikey;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * 真实链路集成测试：通过 RESTEasy Reactive 完整 filter 链（不是反射调用私有方法）
 * 验证 API key 认证 + 跨租户隔离 + CNL source 长度 DoS 上限。
 *
 * <p>补足 {@link ApiKeyAuthFilterTenantOverwriteTest}（反射单测，绕过了 filter
 * 顺序 / TenantFilter / async cache-miss 路径）的盲区。这里用真实 HTTP 请求，
 * 经过 ApiKeyAuthFilter → TenantFilter → 资源方法的实际链路。
 *
 * <p>测试 profile 打开 apikey 鉴权（默认 test profile 关闭），并预置一个有效 key
 * 到验证缓存（{@link ApiKeyVerifierService#seedCacheForTest}），从而无需真实
 * cloud verify 即可模拟"持有有效 key 的调用方"。signature 仍关闭以贴近生产
 * （生产 k3s 也关 signature，正是 ApiKeyAuthFilter 存在的原因）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(ApiKeyTenantIsolationIT.ApiKeyEnabledProfile.class)
class ApiKeyTenantIsolationIT {

    /** 打开 apikey 鉴权的测试 profile（其余安全层维持基类 test 配置）。 */
    public static class ApiKeyEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.security.apikey.enabled", "true",
                "aster.security.signature.enabled", "false"
            );
        }
    }

    private static final String VALID_KEY = "ak_test_valid_tenant_a";
    private static final String TENANT_A = "tenant-a";

    @Inject
    ApiKeyVerifierService verifier;

    @BeforeEach
    void seedKey() {
        // 预置一个绑定 tenant-a 的有效 key，模拟 cloud verify 成功结果。
        verifier.seedCacheForTest(
            VALID_KEY,
            ApiKeyVerifyResult.valid("ak-id-1", "user-1", TENANT_A, "pro", "active"));
    }

    private static String evalBody() {
        return "{\"policyModule\":\"aster.x\",\"policyFunction\":\"y\",\"context\":[]}";
    }

    @Test
    void missingApiKey_returns401() {
        given()
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(401)
            .body(containsString("missing_authorization"));
    }

    @Test
    void bogusApiKey_returns401() {
        given()
            .header("Authorization", "Bearer ak_does_not_exist")
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(401);
    }

    @Test
    void validKey_matchingTenantHeader_passesAuthLayer() {
        // header 租户与 key 租户一致 → 过认证层（之后可能因 module 不存在等
        // 返回业务错误，但绝不是 401/403 鉴权错误）。
        int code = given()
            .header("Authorization", "Bearer " + VALID_KEY)
            .header("X-Tenant-Id", TENANT_A)
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .extract().statusCode();
        org.junit.jupiter.api.Assertions.assertNotEquals(401, code, "一致租户不应 401");
        org.junit.jupiter.api.Assertions.assertNotEquals(403, code, "一致租户不应 403");
    }

    @Test
    void validKey_mismatchedTenantHeader_returns403() {
        // 核心跨租户隔离断言：持有 tenant-a 的有效 key，却带 X-Tenant-Id: victim
        // → 必须 403 tenant_mismatch（真实 filter 链路，不是反射）。
        given()
            .header("Authorization", "Bearer " + VALID_KEY)
            .header("X-Tenant-Id", "victim-tenant")
            .contentType("application/json")
            .body(evalBody())
        .when()
            .post("/api/v1/policies/evaluate")
        .then()
            .statusCode(403)
            .body(containsString("tenant_mismatch"));
    }

    @Test
    void oversizedSource_rejectedByBeanValidationBeforeExpensiveWork() {
        // CNL source 长度上限（算法复杂度 DoS 防护）走真实 bean validation。
        // 注意：/schema 是匿名元数据端点（不在 ApiKeyAuthFilter.shouldProtect
        // 内），故此处不带 Authorization——本用例验证的是 @Size 上限在真实
        // 请求链路上把 >64KB 的 source 快速 400，而非进入超线性解析；API key
        // 鉴权由上面的 evaluate 用例覆盖。
        StringBuilder big = new StringBuilder(70_000);
        big.append("Module m.\\n\\nRule r given x as Number, produce:\\n  Return x. ");
        while (big.length() < 70_000) big.append('x');
        String body = "{\"source\":\"" + big + "\"}";

        given()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "MEMBER")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/api/v1/policies/schema")
        .then()
            .statusCode(400);
    }

    @Test
    void validKey_typicalSource_schemaSucceeds() {
        // 合法小策略仍正常工作（上限不误伤真实用例）。
        String body = "{\"source\":\"Module m.\\n\\nRule r given amount as Number, produce:\\n  Return amount < 100.\"}";
        given()
            .header("X-Tenant-Id", TENANT_A)
            .header("X-User-Role", "MEMBER")
            .contentType("application/json")
            .body(body)
        .when()
            .post("/api/v1/policies/schema")
        .then()
            .statusCode(200)
            .body(containsString("amount"));
    }
}
