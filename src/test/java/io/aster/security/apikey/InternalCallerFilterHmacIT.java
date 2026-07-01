package io.aster.security.apikey;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * 红队 P0-C 端到端验证：InternalCallerFilter 的加固 HMAC 走真实 filter 链
 * （@ServerRequestFilter body 缓存 + 7 行 canonical + nonce 去重）。
 *
 * <p>纯函数 canonical 由 {@link InternalCallerFilterTest} 覆盖；这里验证**活的 filter**：
 * <ul>
 *   <li>正确签名 → 越过 filter（非 403）</li>
 *   <li>改 body（原签名） → 403 invalid_signature（body 已进签名）</li>
 *   <li>改 tenant → 403（tenant 已进签名）</li>
 *   <li>缺 nonce → 403（nonce 必填）</li>
 *   <li>重放同 nonce → 409（UsedNonce 原子去重）</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(InternalCallerFilterHmacIT.HmacProfile.class)
class InternalCallerFilterHmacIT {

    private static final String HMAC_KEY = "it-internal-caller-key-32bytes-min!";
    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String TENANT = "tenant-it-c";
    private static final String ROLE = "MEMBER";

    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                // 关闭全局请求签名，隔离测试 InternalCallerFilter 这一层
                "aster.security.signature.enabled", "false",
                // 确保不走 trial/public 旁路，强制 REQUIRE_HMAC
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false"
            );
        }
    }

    private static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** canonical：method\npath\nts\nnonce\nbodySha256\ntenant\nrole。 */
    private static String canonical(long ts, String nonce, String body, String tenant, String role) {
        return "POST\n" + PATH + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body) + "\n" + tenant + "\n" + role;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    private static String uniqueNonce(String tag) {
        return "it-c-" + tag + "-" + System.nanoTime();
    }

    // SourcePolicyRequest：source + context 必填。用最小可编译 CNL，context 为空对象。
    private static final String VALID_BODY =
        "{\"source\":\"Module probe.\\nRule main given seed as Int, produce Int:\\n  Return 1.\","
        + "\"context\":{\"seed\":0},\"functionName\":\"main\",\"locale\":\"en-US\"}";

    @Test
    void correctlySignedRequest_passesFilter() {
        long ts = nowSec();
        String nonce = uniqueNonce("ok");
        String sig = sign(canonical(ts, nonce, VALID_BODY, TENANT, ROLE));

        // 越过 filter 的判据：不是 403（可能 200/400 视下游校验，但绝不能被 filter 403 掉）。
        int status = given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(VALID_BODY)
            .when().post(PATH)
            .then().extract().statusCode();

        org.junit.jupiter.api.Assertions.assertNotEquals(403, status,
            "正确签名必须越过 InternalCallerFilter（非 403）");
    }

    @Test
    void tamperedBody_rejected403() {
        long ts = nowSec();
        String nonce = uniqueNonce("body");
        // 用原 body 签名，却发送不同 body（模拟 5min 内改请求体烧预算）。
        String sig = sign(canonical(ts, nonce, VALID_BODY, TENANT, ROLE));
        String tamperedBody = VALID_BODY.replace("Return 1", "Return 999");

        given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(tamperedBody)
            .when().post(PATH)
            .then().statusCode(403);
    }

    @Test
    void tamperedTenant_rejected403() {
        long ts = nowSec();
        String nonce = uniqueNonce("tenant");
        String sig = sign(canonical(ts, nonce, VALID_BODY, TENANT, ROLE));

        given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", "other-tenant") // 与签名的 TENANT 不符
            .header("X-User-Role", ROLE)
            .body(VALID_BODY)
            .when().post(PATH)
            .then().statusCode(403);
    }

    @Test
    void missingNonce_rejected403() {
        long ts = nowSec();
        String nonce = uniqueNonce("nononce");
        String sig = sign(canonical(ts, nonce, VALID_BODY, TENANT, ROLE));

        given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            // 故意不发 X-Aster-Nonce
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(VALID_BODY)
            .when().post(PATH)
            .then().statusCode(403);
    }

    @Test
    void replayedNonce_rejected409() {
        long ts = nowSec();
        String nonce = uniqueNonce("replay");
        String sig = sign(canonical(ts, nonce, VALID_BODY, TENANT, ROLE));

        // 第一次：越过 filter（非 403/409）
        int first = given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(VALID_BODY)
            .when().post(PATH)
            .then().extract().statusCode();
        org.junit.jupiter.api.Assertions.assertNotEquals(403, first, "首次正确签名应越过 filter");
        org.junit.jupiter.api.Assertions.assertNotEquals(409, first, "首次不应是重放");

        // 第二次：完全相同的签名 + nonce → UsedNonce 已占用 → 409。
        given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(VALID_BODY)
            .when().post(PATH)
            .then().statusCode(409);
    }

    // 红队 P0-C：只认 v2（nonce 必填）。旧 v1（3 行无 nonce）无真实用户，已删除兼容路径。
    // v1 形态（缺 X-Aster-Nonce）现在等同缺凭证 → 403，由 missingNonce_rejected403 覆盖。

    @Test
    void oversizedBody_rejected403() {
        // 红队 P0-C（Codex 复核建议）：body > 1MB 上限 → filter 快速失败 403 body_too_large。
        // 构造 >1MB 的 JSON body（source 字段塞大量字符）。签名对该大 body 正确签，
        // 证明拦截发生在 body-size 守卫（非签名失败）。
        long ts = nowSec();
        String nonce = uniqueNonce("big");
        StringBuilder big = new StringBuilder(1_200_000);
        big.append("{\"source\":\"");
        for (int i = 0; i < 1_200_000; i++) big.append('x');
        big.append("\",\"context\":{}}");
        String body = big.toString();
        String sig = sign(canonical(ts, nonce, body, TENANT, ROLE));

        given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sig)
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(body)
            .when().post(PATH)
            .then()
            // 全局 quarkus.http.limits.max-body-size=1M 或 filter 的 MAX_INTERNAL_BODY_BYTES
            // 都会拒；两者都是 fail-closed（4xx），断言非 2xx 越过。
            .statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(400));
    }

}
