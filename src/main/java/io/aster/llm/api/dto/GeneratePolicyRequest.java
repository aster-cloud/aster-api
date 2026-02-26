package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * NL-to-CNL 策略生成请求
 *
 * @param goal           自然语言需求描述
 * @param locale         目标语言代码 (en-US / zh-CN / de-DE)
 * @param existingSource 现有策略代码（修改/优化场景）
 * @param schema         期望的参数 schema JSON（可选，约束生成输出）
 * @param model          LLM 模型覆盖（可选，默认使用全局配置）
 */
public record GeneratePolicyRequest(
    @NotBlank(message = "goal 不能为空")
    String goal,

    String locale,

    String existingSource,

    Object schema,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
