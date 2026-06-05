package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
    // 所有拼入 prompt 的用户字段都要设上限，否则可绕过 source 上限制造超大
    // prompt（内存 + LLM 调用成本）。existingSource 与 policy.rest.model
    // .CnlSourceLimits.MAX_SOURCE_LENGTH=65536 对齐；schema 为 Object，由
    // PromptComposer.serializeSchema 的序列化截断 + 全局 body 上限兜底。
    @NotBlank(message = "goal 不能为空")
    @Size(max = 8_192, message = "goal 长度超过上限（最多 8192 字符）")
    String goal,

    String locale,

    @Size(max = 65_536, message = "existingSource 长度超过上限（最多 65536 字符）")
    String existingSource,

    Object schema,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
