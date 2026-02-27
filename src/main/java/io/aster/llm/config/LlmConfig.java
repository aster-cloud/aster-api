package io.aster.llm.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * LLM 集成配置
 *
 * 通过 aster.llm.* 前缀的配置项管理 LLM 连接和行为。
 * 支持多提供商（OpenAI / Claude / 自定义兼容端点）。
 */
@ConfigMapping(prefix = "aster.llm")
public interface LlmConfig {

    @WithDefault("true")
    boolean enabled();

    /** LLM 提供商标识：openai / anthropic / custom */
    @WithDefault("openai")
    String provider();

    @WithDefault("https://api.openai.com")
    String baseUrl();

    @WithDefault("gpt-4o-mini")
    String model();

    @WithDefault("0.2")
    double temperature();

    @WithDefault("2048")
    int maxTokens();

    /** 连接超时 */
    @WithDefault("30s")
    Duration timeout();

    /** 读超时（SSE 流式场景需要较长） */
    @WithDefault("120s")
    Duration readTimeout();

    /** 全局 API Key（多租户场景可被租户专属 Key 覆盖） */
    Optional<String> apiKey();

    /** Key 来源：config / db */
    @WithDefault("config")
    String keySource();

    Validation validation();

    Prompt prompt();

    Cache cache();

    interface Validation {
        /** 编译校验最大重试次数（含首次生成） */
        @WithDefault("5")
        int maxAttempts();
    }

    interface Prompt {
        @WithDefault("prompts")
        String basePath();

        @WithDefault("zh")
        String defaultLocale();
    }

    interface Cache {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("10m")
        Duration ttl();

        @WithDefault("1000")
        int maxSize();
    }
}
