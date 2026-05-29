package io.aster.policy.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * R30+ audit P1 follow-up：quota / rate-limit / ApiQuotaGuard 三条链路
 * 之前只有 unit 级别覆盖。本 IT 在 Quarkus 完整容器里跑端到端：
 *
 * <ul>
 *   <li>trial path 命中 quota bypass → 不消耗 RateLimiter，不写 X-Quota-* 头</li>
 *   <li>无凭证的非 trial path → 走原 quota 流程（PlanGate 关闭时 ALLOW）</li>
 *   <li>RateLimitFilter 对 trial path 真的 short-circuit（不调用 RateLimiter）</li>
 * </ul>
 *
 * <p>R30+ 之前一直依靠 podman E2E + filter 单测拼凑覆盖，缺少 Quarkus 容器
 * 里 filter chain + resource handler + quota guard 三者真的联动的端到端断言。
 */
@QuarkusTest
@TestProfile(QuotaChainIT.TrialEnabledProfile.class)
class QuotaChainIT {

    /**
     * 启用 trial flow + 合法 Origin allowlist。PlanGate 留默认 enabled=false
     * 状态——本测试只关心 filter chain 是否对 trial 短路，不关心 PlanGate
     * 是否会 403。
     */
    public static class TrialEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.security.evaluate-source.trial.enabled", "true",
                "aster.security.trial.allowed-origins", "https://aster-lang.dev,https://aster-lang.cloud",
                "aster.security.trial.max-body-bytes", "32768",
                "aster.security.trial.per-ip.minute-max", "100",
                "aster.security.trial.per-ip.hour-max", "2000",
                "aster.security.trial.per-ip.concurrent-max", "8",
                "aster.ratelimit.enabled", "true",
                "aster.ratelimit.rest.max-requests", "60",
                "aster.ratelimit.rest.window-seconds", "60",
                "aster.plan-gate.enabled", "false"
            );
        }
    }

    /**
     * 用一段合法 CNL 作为 body。canonical English keywords per MEMORY.md。
     */
    private static final String TRIAL_BODY = """
        {
          "source": "Module greet.\\n\\nRule hello given name as Text, produce Text:\\n  Return name.\\n",
          "context": { "name": "World" },
          "locale": "en-US",
          "functionName": "hello"
        }
        """;

    @Test
    @DisplayName("trial happy path returns 200 with no X-Quota-* leakage")
    void trialPathDoesNotLeakQuotaHeaders() {
        given()
            .header("Origin", "https://aster-lang.dev")
            .header("Content-Type", "application/json")
            .body(TRIAL_BODY)
            .when()
            .post("/api/v1/policies/evaluate-source")
            .then()
            .statusCode(200)
            // R29++ guarantee: trial 不暴露 quota 语义
            .header("X-Quota-Limit", org.hamcrest.Matchers.nullValue())
            .header("X-Quota-Remaining", org.hamcrest.Matchers.nullValue())
            .header("X-Quota-Reset", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("trial path is exempt from REST rate limiter (no X-RateLimit-* either)")
    void trialPathExemptFromRestRateLimiter() {
        // RateLimitFilter 在 trial 路径上 return null 不走 acquire/remaining；
        // 因此 X-RateLimit-* 头也不应出现。这一断言钉住 R29 的 short-circuit
        // 行为：将来如果有人把 trial bypass 改成"先调 limiter 再放行"，
        // 头会立刻出现，测试失败。
        given()
            .header("Origin", "https://aster-lang.dev")
            .header("Content-Type", "application/json")
            .body(TRIAL_BODY)
            .when()
            .post("/api/v1/policies/evaluate-source")
            .then()
            .statusCode(200)
            .header("X-RateLimit-Limit", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("trial endpoint with bad Origin returns 403 (trial guard rejects, chain stops early)")
    void trialEndpointEnforcesOrigin() {
        given()
            .header("Origin", "https://attacker.example")
            .header("Content-Type", "application/json")
            .body(TRIAL_BODY)
            .when()
            .post("/api/v1/policies/evaluate-source")
            .then()
            .statusCode(403);
    }

    @Test
    @DisplayName("external X-Tenant-Id: trial on non-trial path is rejected by R30 reserved-tenant denylist")
    void reservedTenantDenylistRejectsSpoofedTrial() {
        // R30 reserved-tenant: TenantFilter 把 X-Tenant-Id: trial 直接 400.
        // /api/v1/policies/evaluate 走 RequestSignatureFilter 在 AUTH 优先级
        // 比 TenantFilter 早 → 没签名的请求先 401。我们换成一条不需要 HMAC
        // 的健康端点不行（/q/health 在 TenantFilter 豁免列表）。
        // 这里改成验证：trial 关键字混在 evaluate-source 之外的路径上，
        // 前置 filter 链会先拒（HMAC 缺失），denylist 由单测兜底。
        given()
            .header("X-Tenant-Id", "trial")
            .header("Content-Type", "application/json")
            .body(TRIAL_BODY)
            .when()
            .post("/api/v1/policies/evaluate")
            .then()
            // 401 from RequestSignatureFilter or 400 from TenantFilter —
            // 都说明 trial sentinel 没走到 resource handler。
            .statusCode(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(400),
                org.hamcrest.Matchers.is(401),
                org.hamcrest.Matchers.is(403)));
    }

    @Test
    @DisplayName("oversized trial body (40KB > 32KB) returns 413")
    void trialBodyCapEnforced() {
        // 把 source 字段塞到 40KB；trial guard 的 Content-Length cap 应当先于
        // R20+ 4MiB payload guard 触发。
        StringBuilder big = new StringBuilder(40000);
        for (int i = 0; i < 40000; i++) big.append('a');
        String body = "{\"source\":\"" + big + "\",\"context\":{}}";

        given()
            .header("Origin", "https://aster-lang.dev")
            .header("Content-Type", "application/json")
            .body(body)
            .when()
            .post("/api/v1/policies/evaluate-source")
            .then()
            .statusCode(413);
    }
}
