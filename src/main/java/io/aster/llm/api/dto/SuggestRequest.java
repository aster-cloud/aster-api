package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
// Phase 2 BYOK：忽略顶层 _byok envelope（见 GeneratePolicyRequest 注释）。
@JsonIgnoreProperties(ignoreUnknown = true)
public record SuggestRequest(
    // 64KB 上限，与 policy.rest.model.CnlSourceLimits.MAX_SOURCE_LENGTH 对齐。
    @NotBlank(message = "source 不能为空")
    @Size(max = 65_536, message = "source 长度超过上限（最多 65536 字符）")
    String source,

    String locale,

    // focus 是短关注点提示（performance / readability / …），会原样拼入 prompt。
    // 设小上限防止它成为绕过 source 上限的超大 prompt 注入面。
    @Size(max = 512, message = "focus 长度超过上限（最多 512 字符）")
    String focus,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
