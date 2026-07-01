package io.aster.policy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R31-4：Turnstile verifier 行为单测。
 *
 * <p>不调真实 cf endpoint —— 只验证开关 + fail-closed 语义：
 * <ul>
 *   <li>enabled=false → 任何 token 一律 true（dev / podman）</li>
 *   <li>enabled=true + token 空 → false</li>
 *   <li>enabled=true + secret 缺失 → false（fail-closed）</li>
 * </ul>
 */
class TurnstileVerifierTest {

    private TurnstileVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new TurnstileVerifier();
        set("enabled", false);
        set("secret", Optional.<String>empty());
        set("timeoutMs", 3000);
    }

    private void set(String name, Object value) throws Exception {
        Field f = TurnstileVerifier.class.getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Integer i) f.setInt(verifier, i);
        else if (value instanceof Boolean b) f.setBoolean(verifier, b);
        else f.set(verifier, value);
    }

    @Test
    @DisplayName("disabled → any token returns true (dev/podman)")
    void disabledAlwaysPasses() throws Exception {
        set("enabled", false);
        assertTrue(verifier.verify(null, "1.1.1.1"));
        assertTrue(verifier.verify("", "1.1.1.1"));
        assertTrue(verifier.verify("anything", "1.1.1.1"));
    }

    @Test
    @DisplayName("enabled + null token → false")
    void enabledRejectsNullToken() throws Exception {
        set("enabled", true);
        set("secret", Optional.of("test-secret"));
        assertFalse(verifier.verify(null, "1.1.1.1"));
    }

    @Test
    @DisplayName("enabled + blank token → false")
    void enabledRejectsBlankToken() throws Exception {
        set("enabled", true);
        set("secret", Optional.of("test-secret"));
        assertFalse(verifier.verify("   ", "1.1.1.1"));
    }

    @Test
    @DisplayName("enabled but secret missing → false (fail-closed)")
    void enabledMissingSecretFailsClosed() throws Exception {
        set("enabled", true);
        set("secret", Optional.<String>empty());
        assertFalse(verifier.verify("any-token", "1.1.1.1"));
    }

    @Test
    @DisplayName("enabled but secret blank → false (fail-closed)")
    void enabledBlankSecretFailsClosed() throws Exception {
        set("enabled", true);
        set("secret", Optional.of(""));
        assertFalse(verifier.verify("any-token", "1.1.1.1"));
    }

    // ── 红队 P2-J：缓存 key 绑 IP（防跨 IP token 重放绕过 per-IP trial 限流）────────

    private String cacheKey(String token, String ip) throws Exception {
        var m = TurnstileVerifier.class.getDeclaredMethod("cacheKey", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, token, ip);
    }

    @Test
    @DisplayName("同 token 不同 IP → 不同 cache key（防跨 IP 重放）")
    void cacheKeyDiffersByIp() throws Exception {
        String kA = cacheKey("same-token", "1.1.1.1");
        String kB = cacheKey("same-token", "2.2.2.2");
        org.junit.jupiter.api.Assertions.assertNotEquals(kA, kB,
            "同一 token 换 IP 必须产生不同 cache key，否则 60s 内可跨 IP 复用绕过限流");
    }

    @Test
    @DisplayName("同 token 同 IP → 相同 cache key（缓存正常命中）")
    void cacheKeyStableForSameTokenAndIp() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(
            cacheKey("t", "1.1.1.1"), cacheKey("t", "1.1.1.1"),
            "同 token 同 IP 应命中同一缓存桶");
    }

    @Test
    @DisplayName("不同 token 同 IP → 不同 cache key")
    void cacheKeyDiffersByToken() throws Exception {
        org.junit.jupiter.api.Assertions.assertNotEquals(
            cacheKey("token-a", "1.1.1.1"), cacheKey("token-b", "1.1.1.1"));
    }

    @Test
    @DisplayName("null IP 归一为空串，仍可稳定生成 key（不 NPE）")
    void cacheKeyHandlesNullIp() throws Exception {
        String k1 = cacheKey("t", null);
        String k2 = cacheKey("t", null);
        org.junit.jupiter.api.Assertions.assertEquals(k1, k2);
        // null IP 与显式空串 IP 归一到同一桶
        org.junit.jupiter.api.Assertions.assertEquals(k1, cacheKey("t", ""));
    }
}
