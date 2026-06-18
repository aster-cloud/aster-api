package io.aster.policy.security;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminHmacVerifier} 验签测试（ADR 0021）。
 *
 * <p>不接 Redis（{@code Instance<RedisDataSource>} 反射注入 null → claimNonce 退回本地
 * Caffeine）。验：缺头拒、stale ts 拒、错签名拒、合法签名过、nonce 重放拒。
 */
@DisplayName("AdminHmacVerifier")
class AdminHmacVerifierTest {

    private static final String KEY = "test-hmac-key-0123456789";
    private AdminHmacVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new AdminHmacVerifier();
        setField("hmacKey", Optional.of(KEY));
        // redisDataSource 留 null → claimNonce 退回本地（@Inject Instance 在单测不注入）。
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AdminHmacVerifier.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(verifier, value);
    }

    private static String sign(String method, String path, long ts, String nonce,
                               String ct, long len, String bodySha) {
        String canonical = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n"
            + (ct == null ? "" : ct) + "\n" + len + "\n" + (bodySha == null ? "" : bodySha);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpHeaders headers(String ts, String nonce, String sig) {
        HttpHeaders h = mock(HttpHeaders.class);
        when(h.getHeaderString("X-Aster-Timestamp")).thenReturn(ts);
        when(h.getHeaderString("X-Aster-Nonce")).thenReturn(nonce);
        when(h.getHeaderString("X-Internal-Signature")).thenReturn(sig);
        return h;
    }

    @Test
    @DisplayName("合法签名 → 通过")
    void validSignaturePasses() {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "nonce-valid-1";
        String sig = sign("DELETE", "/api/v1/admin/messages/en-US", ts, nonce, null, 0, null);
        verifier.verify(headers(String.valueOf(ts), nonce, sig),
            "DELETE", "/api/v1/admin/messages/en-US", null, 0, null);
        // 不抛 = 通过
    }

    @Test
    @DisplayName("缺签名头 → 403 missing_signature_headers")
    void missingHeadersRejected() {
        assertThatThrownBy(() -> verifier.verify(headers(null, null, null),
            "PUT", "/p", null, 0, null))
            .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @DisplayName("stale timestamp（超 5min）→ 403")
    void staleTimestampRejected() {
        long ts = System.currentTimeMillis() / 1000 - 1000; // 16+ min 前
        String nonce = "nonce-stale";
        String sig = sign("PUT", "/p", ts, nonce, null, 0, null);
        assertThatThrownBy(() -> verifier.verify(headers(String.valueOf(ts), nonce, sig),
            "PUT", "/p", null, 0, null))
            .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @DisplayName("错误签名 → 403 invalid_signature")
    void wrongSignatureRejected() {
        long ts = System.currentTimeMillis() / 1000;
        assertThatThrownBy(() -> verifier.verify(
            headers(String.valueOf(ts), "nonce-wrong", "deadbeef"),
            "PUT", "/p", null, 0, null))
            .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @DisplayName("nonce 重放 → 第二次 403 replayed_nonce")
    void nonceReplayRejected() {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "nonce-replay";
        String sig = sign("PUT", "/api/v1/admin/messages/en-US", ts, nonce, null, 0, null);
        // 首次通过
        verifier.verify(headers(String.valueOf(ts), nonce, sig),
            "PUT", "/api/v1/admin/messages/en-US", null, 0, null);
        // 同 nonce 重放被拒
        assertThatThrownBy(() -> verifier.verify(headers(String.valueOf(ts), nonce, sig),
            "PUT", "/api/v1/admin/messages/en-US", null, 0, null))
            .isInstanceOf(WebApplicationException.class)
            .hasMessageContaining("");
    }

    @Test
    @DisplayName("hmac-key 未配置 → 403 hmac_not_configured")
    void noKeyConfiguredRejected() throws Exception {
        setField("hmacKey", Optional.empty());
        assertThatThrownBy(() -> verifier.verify(headers("1", "n", "s"),
            "PUT", "/p", null, 0, null))
            .isInstanceOf(WebApplicationException.class);
    }
}
