package io.aster.billing;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * PlanCacheResource 集成测试
 *
 * 验证 DELETE /api/internal/plan-cache/{tenantId} 的 HMAC 验签：
 *   - 缺签名头 → 401
 *   - 时间戳过期 → 401
 *   - 错误签名 → 401
 *   - 正确签名 → 200
 */
@QuarkusTest
@TestProfile(PlanCacheResourceIT.HmacProfile.class)
class PlanCacheResourceIT {

    private static final String HMAC_KEY = "it-shared-key-32bytes-minimum-len";

    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.enabled", "true",
                "aster.plan-gate.hmac-key", HMAC_KEY
            );
        }
    }

    private static String sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void missingHeaders_returns401() {
        given()
            .when()
            .delete("/api/internal/plan-cache/tenant-1")
            .then()
            .statusCode(401)
            .body(containsString("missing"));
    }

    @Test
    void staleTimestamp_returns401() {
        long old = System.currentTimeMillis() / 1000 - 600; // 10 分钟前，超 5 min 窗口
        String path = "/api/internal/plan-cache/tenant-1";
        String sig = sign("DELETE\n" + path + "\n" + old);

        given()
            .header("X-Aster-Timestamp", String.valueOf(old))
            .header("X-Aster-Signature", sig)
            .when()
            .delete(path)
            .then()
            .statusCode(401)
            .body(containsString("stale"));
    }

    @Test
    void wrongSignature_returns401() {
        long now = System.currentTimeMillis() / 1000;
        String path = "/api/internal/plan-cache/tenant-1";

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Signature", "deadbeef".repeat(8)) // 非空但错的 64 字节 hex
            .when()
            .delete(path)
            .then()
            .statusCode(401)
            .body(containsString("invalid signature"));
    }

    @Test
    void validSignature_returns200_andInvalidates() {
        long now = System.currentTimeMillis() / 1000;
        String path = "/api/internal/plan-cache/tenant-1";
        String sig = sign("DELETE\n" + path + "\n" + now);

        given()
            .header("X-Aster-Timestamp", String.valueOf(now))
            .header("X-Aster-Signature", sig)
            .when()
            .delete(path)
            .then()
            .statusCode(200)
            .body(containsString("tenant-1"));
    }
}
