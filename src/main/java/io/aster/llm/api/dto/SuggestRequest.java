package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 策略优化建议请求
 *
 * @param source 现有策略源代码
 * @param locale 目标语言代码
 * @param focus  优化关注点（可选：performance / readability / simplification）
 * @param model  LLM 模型覆盖（可选）
 */
public record SuggestRequest(
    @NotBlank(message = "source 不能为空")
    String source,

    String locale,

    String focus,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
