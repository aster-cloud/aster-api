package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 策略优化建议请求
 *
 * @param source 现有策略源代码
 * @param locale 目标语言代码
 * @param focus  优化关注点（可选：performance / readability / simplification）
 * @param model  LLM 模型覆盖（可选）
 */
public record SuggestRequest(
    // 64KB 上限，与 policy.rest.model.CnlSourceLimits.MAX_SOURCE_LENGTH 对齐。
    @NotBlank(message = "source 不能为空")
    @Size(max = 65_536, message = "source 长度超过上限（最多 65536 字符）")
    String source,

    String locale,

    String focus,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
