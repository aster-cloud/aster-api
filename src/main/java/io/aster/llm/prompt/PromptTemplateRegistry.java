package io.aster.llm.prompt;

import io.aster.llm.config.LlmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板注册表
 *
 * 从 classpath 资源加载 prompt 模板，支持 locale 切换和缓存。
 * 模板路径格式：{basePath}/{category}/{templateName}_{locale}.txt
 */
@ApplicationScoped
public class PromptTemplateRegistry {

    private static final Logger LOG = Logger.getLogger(PromptTemplateRegistry.class);

    @Inject
    LlmConfig config;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载模板
     *
     * @param category 分类：system / developer
     * @param name     模板名：system_base / policy_gen / policy_repair
     * @param locale   语言代码：zh / en / de
     * @return 模板内容
     */
    public String load(String category, String name, String locale) {
        String normalizedLocale = normalizeLocale(locale);
        String cacheKey = category + "/" + name + "_" + normalizedLocale;

        return cache.computeIfAbsent(cacheKey, key -> {
            String path = config.prompt().basePath() + "/" + category + "/" + name + "_" + normalizedLocale + ".txt";
            String content = loadResource(path);

            if (content == null) {
                // 回退到默认 locale
                String fallbackPath = config.prompt().basePath() + "/" + category + "/" + name + "_" + config.prompt().defaultLocale() + ".txt";
                LOG.warnf("模板 %s 不存在，回退到 %s", path, fallbackPath);
                content = loadResource(fallbackPath);
            }

            if (content == null) {
                LOG.errorf("模板加载失败: %s", path);
                return "";
            }

            LOG.debugf("模板已加载: %s (%d chars)", path, content.length());
            return content;
        });
    }

    /**
     * 将 locale 代码简化为短形式
     * zh-CN / zh → zh, en-US / en → en, de-DE / de → de
     */
    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return config.prompt().defaultLocale();
        }
        // 取 locale 前缀
        String lower = locale.toLowerCase();
        if (lower.contains("-") || lower.contains("_")) {
            return lower.split("[-_]")[0];
        }
        return lower;
    }

    private String loadResource(String path) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.errorf(e, "加载资源失败: %s", path);
            return null;
        }
    }
}
