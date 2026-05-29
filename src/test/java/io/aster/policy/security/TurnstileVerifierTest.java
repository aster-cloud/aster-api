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
}
