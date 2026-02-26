package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 策略解释请求
 *
 * @param source 要解释的 CNL 策略代码
 * @param locale 输出语言
 * @param traceData 决策追踪数据（可选，用于解释执行路径）
 */
public record ExplainRequest(
    @NotBlank(message = "source 不能为空")
    String source,

    String locale,

    Object traceData
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
