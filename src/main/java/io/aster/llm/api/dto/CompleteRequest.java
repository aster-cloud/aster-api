package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码补全请求
 *
 * @param prefix 光标前的代码上下文（最近 5 行）
 * @param locale CNL 语言
 * @param model  模型覆盖（补全建议使用轻量模型如 gpt-4o-mini）
 */
public record CompleteRequest(
    @NotBlank(message = "prefix 不能为空")
    String prefix,

    String locale,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale;
    }
}
