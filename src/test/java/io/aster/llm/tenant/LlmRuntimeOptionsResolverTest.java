package io.aster.llm.tenant;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmRuntimeOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LlmRuntimeOptionsResolver 单测（Phase 2 BYOK）：平台/BYOK 解析、SSRF allowlist、失败不回退。
 */
class LlmRuntimeOptionsResolverTest {

    private LlmConfig config;
    private TenantLlmKeyProvider keyProvider;
    private LlmRuntimeOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        config = mock(LlmConfig.class);
        keyProvider = mock(TenantLlmKeyProvider.class);
        when(config.provider()).thenReturn("rightcode");
        when(config.baseUrl()).thenReturn("https://right.codes/codex/v1");
        when(keyProvider.getApiKey("tenant-1", "rightcode")).thenReturn("platform-key");

        resolver = new LlmRuntimeOptionsResolver();
        resolver.config = config;
        resolver.keyProvider = keyProvider;
    }

    @Test
    @DisplayName("无 BYOK → 平台凭证（provider/baseUrl 来自 config，key 来自 keyProvider）")
    void resolvesPlatformWhenNoByok() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", null);
        assertThat(opts.source()).isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
        assertThat(opts.provider()).isEqualTo("rightcode");
        assertThat(opts.baseUrl()).isEqualTo("https://right.codes/codex/v1");
        assertThat(opts.apiKey()).isEqualTo("platform-key");
        assertThat(opts.usedByok()).isFalse();
    }

    @Test
    @DisplayName("非法 BYOK（provider/key 空）→ 回退平台（isValid=false 不算 BYOK）")
    void invalidByokFallsBackToPlatform() {
        assertThat(resolver.resolve("tenant-1", new ByokOverride(null, "k")).source())
            .isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
        assertThat(resolver.resolve("tenant-1", new ByokOverride("openai", "")).source())
            .isEqualTo(LlmRuntimeOptions.Source.PLATFORM);
    }

    @Test
    @DisplayName("BYOK openai → 固定 baseUrl api.openai.com（不信外部 URL）")
    void resolvesByokOpenAi() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user"));
        assertThat(opts.source()).isEqualTo(LlmRuntimeOptions.Source.BYOK);
        assertThat(opts.provider()).isEqualTo("openai");
        assertThat(opts.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(opts.apiKey()).isEqualTo("sk-user");
        assertThat(opts.usedByok()).isTrue();
    }

    @Test
    @DisplayName("BYOK anthropic → 固定 baseUrl api.anthropic.com")
    void resolvesByokAnthropic() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("anthropic", "sk-ant"));
        assertThat(opts.provider()).isEqualTo("anthropic");
        assertThat(opts.baseUrl()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("BYOK provider 大小写/空格规范化")
    void normalizesProvider() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1", new ByokOverride("  OpenAI ", "sk"));
        assertThat(opts.provider()).isEqualTo("openai");
        assertThat(opts.baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("★SSRF 防护：未知 provider（含 vertex）→ 抛错，绝不回退平台 key")
    void rejectsUnknownProviderNoFallback() {
        assertThatThrownBy(() -> resolver.resolve("tenant-1", new ByokOverride("vertex", "k")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vertex");
        assertThatThrownBy(() -> resolver.resolve("tenant-1", new ByokOverride("evil-host.internal", "k")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
