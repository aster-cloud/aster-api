package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 代码补全请求
 *
 * @param prefix 光标前的代码上下文（最近 5 行）
 * @param locale CNL 语言
 * @param model  模型覆盖（补全建议使用轻量模型如 gpt-4o-mini）
 */
// Phase 2 BYOK：忽略顶层 _byok envelope（见 GeneratePolicyRequest 注释）。
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompleteRequest(
    // prefix 是光标前最近几行，正常很短；设 8KB 上限防止超大 prefix 拼入 prompt。
    @NotBlank(message = "prefix 不能为空")
    @Size(max = 8_192, message = "prefix 长度超过上限（最多 8192 字符）")
    String prefix,

    String locale,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale;
    }
}
