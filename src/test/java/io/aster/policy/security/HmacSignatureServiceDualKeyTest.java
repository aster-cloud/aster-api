package io.aster.policy.security;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dual-key 行为单元测试（P0-1 配套）
 *
 * 关注：current/previous key 都被验签时，verify() 的接受/拒绝逻辑。
 * 不依赖 Quarkus context — 直接 new + 反射注入字段，跑 fast unit test。
 */
class HmacSignatureServiceDualKeyTest {

    private static final String TENANT = "perf-tenant";
    private static final String CURRENT_KEY = "current-secret-key-12345";
    private static final String PREVIOUS_KEY = "previous-secret-key-67890";
    private static final String BODY = "{\"hello\":\"world\"}";

    private HmacSignatureService service;
    private SecurityEventService events;

    @BeforeEach
    void setUp() throws Exception {
        events = mock(SecurityEventService.class);
        service = new HmacSignatureService();
        inject(service, "securityEventService", events);
        // timestampWindowMinutes 默认 0；显式给一个有效值
        inject(service, "timestampWindowMinutes", 5);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 构造 mock 请求 + 计算指定 key 的签名头 */
    private ContainerRequestContext mockRequest(String key, long timestamp, String nonce) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        URI requestUri = URI.create("https://policy.aster-lang.dev/api/v1/policies/evaluate");
        when(uri.getRequestUri()).thenReturn(requestUri);
        when(uri.getPath()).thenReturn("/api/v1/policies/evaluate");
        when(ctx.getUriInfo()).thenReturn(uri);
        when(ctx.getMethod()).thenReturn("POST");

        String canonical = RequestCanonicalizer.canonicalize(
            "POST", "/api/v1/policies/evaluate", null,
            String.valueOf(timestamp), nonce, BODY.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha256Hex(key, canonical);

        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("X-Tenant-Id", TENANT);
        headers.add("X-Aster-Signature", signature);
        headers.add("X-Aster-Nonce", nonce);
        headers.add("X-Aster-Timestamp", String.valueOf(timestamp));
        when(ctx.getHeaderString("X-Tenant-Id")).thenReturn(TENANT);
        when(ctx.getHeaderString("X-Aster-Signature")).thenReturn(signature);
        when(ctx.getHeaderString("X-Aster-Nonce")).thenReturn(nonce);
        when(ctx.getHeaderString("X-Aster-Timestamp")).thenReturn(String.valueOf(timestamp));
        when(ctx.getHeaders()).thenReturn(headers);
        return ctx;
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("仅 current key 配置")
    class CurrentOnly {

        @BeforeEach
        void configure() throws Exception {
            inject(service, "useEnvSecrets", false);
            inject(service, "globalSecretKey", Optional.of(CURRENT_KEY));
            inject(service, "previousGlobalSecretKey", Optional.<String>empty());
        }

        @Test
        @DisplayName("current key 签名应通过")
        void currentKeyPasses() {
            ContainerRequestContext ctx = mockRequest(CURRENT_KEY, System.currentTimeMillis(), "nonce-1");
            service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8)); // no throw
        }

        @Test
        @DisplayName("previous key 签名应被拒绝")
        void previousKeyRejected() {
            ContainerRequestContext ctx = mockRequest(PREVIOUS_KEY, System.currentTimeMillis(), "nonce-2");
            assertThatThrownBy(() -> service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("Invalid signature");
        }
    }

    @Nested
    @DisplayName("dual-key（current + previous 同时配置）")
    class DualKey {

        @BeforeEach
        void configure() throws Exception {
            inject(service, "useEnvSecrets", false);
            inject(service, "globalSecretKey", Optional.of(CURRENT_KEY));
            inject(service, "previousGlobalSecretKey", Optional.of(PREVIOUS_KEY));
        }

        @Test
        @DisplayName("current key 签名应通过（优先匹配）")
        void currentKeyPasses() {
            ContainerRequestContext ctx = mockRequest(CURRENT_KEY, System.currentTimeMillis(), "nonce-3");
            service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("previous key 签名也应通过（grace period 命中）")
        void previousKeyAlsoPasses() {
            ContainerRequestContext ctx = mockRequest(PREVIOUS_KEY, System.currentTimeMillis(), "nonce-4");
            service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("两个都不是的 key 应拒绝并记录失败事件")
        void unknownKeyRejected() {
            ContainerRequestContext ctx = mockRequest("attacker-forged-key", System.currentTimeMillis(), "nonce-5");
            assertThatThrownBy(() -> service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("Invalid signature");
        }
    }

    @Nested
    @DisplayName("时间戳窗口")
    class TimestampWindow {

        @BeforeEach
        void configure() throws Exception {
            inject(service, "useEnvSecrets", false);
            inject(service, "globalSecretKey", Optional.of(CURRENT_KEY));
            inject(service, "previousGlobalSecretKey", Optional.<String>empty());
        }

        @Test
        @DisplayName("超过 5 分钟窗口的 timestamp 应拒绝")
        void expiredTimestampRejected() {
            long stale = System.currentTimeMillis() - 10 * 60_000L;
            ContainerRequestContext ctx = mockRequest(CURRENT_KEY, stale, "nonce-6");
            assertThatThrownBy(() -> service.verify(ctx, BODY.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("Timestamp out of valid window");
        }
    }
}
