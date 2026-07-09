package io.aster.llm.security;

import io.aster.security.net.SsrfGuard;
import io.aster.security.net.ValidatedEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmEndpointPolicyTest {

    private LlmEndpointPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new LlmEndpointPolicy();
        policy.ssrfGuard = new SsrfGuard(host -> List.of(ip("93.184.216.34")));
        policy.endpointAllowlist = Optional.of(List.of(
            "https://llm-gateway.example.com/v1",
            "llm-alt.example.com:8443"
        ));
    }

    private static InetAddress ip(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    @DisplayName("官方 OpenAI/Anthropic endpoint 内置命中")
    void allowsBuiltInOfficialEndpoints() {
        assertThat(policy.validateByokEndpoint("openai", "https://api.openai.com").canonicalHost())
            .isEqualTo("api.openai.com");
        assertThat(policy.validateByokEndpoint("anthropic", "https://api.anthropic.com").canonicalHost())
            .isEqualTo("api.anthropic.com");
    }

    @Test
    @DisplayName("管理员 allowlist host/path/port 命中")
    void allowsConfiguredEndpoint() {
        ValidatedEndpoint endpoint = policy.validateByokEndpoint("openai",
            "https://llm-gateway.example.com/v1/chat");

        assertThat(endpoint.canonicalHost()).isEqualTo("llm-gateway.example.com");
        assertThat(endpoint.pathPrefix()).isEqualTo("/v1/chat");
    }

    @Test
    @DisplayName("allowlist 可约束非 443 port")
    void allowsConfiguredPort() {
        ValidatedEndpoint endpoint = policy.validateByokEndpoint("openai",
            "https://llm-alt.example.com:8443/v1");

        assertThat(endpoint.port()).isEqualTo(8443);
    }

    @Test
    @DisplayName("未命中 allowlist → fail-closed")
    void rejectsEndpointOutsideAllowlist() {
        assertThatThrownBy(() -> policy.validateByokEndpoint("openai", "https://evil.example.net/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowlist");
    }

    @Test
    @DisplayName("未知 provider → fail-closed")
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> policy.validateByokEndpoint("vertex", "https://llm-gateway.example.com/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vertex");
    }
}
