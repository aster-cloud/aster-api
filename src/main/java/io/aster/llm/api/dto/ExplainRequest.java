package io.aster.llm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 策略解释请求
 *
 * @param source 要解释的 CNL 策略代码
 * @param locale 输出语言
 * @param traceData 决策追踪数据（可选，用于解释执行路径）
 */
public record ExplainRequest(
    // 64KB 上限，与 policy.rest.model.CnlSourceLimits.MAX_SOURCE_LENGTH 对齐：
    // CNL 解析超线性耗时，且 LLM 路径还会叠加模型调用 + 编译校验循环，更贵。
    @NotBlank(message = "source 不能为空")
    @Size(max = 65_536, message = "source 长度超过上限（最多 65536 字符）")
    String source,

    String locale,

    Object traceData
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }
}
