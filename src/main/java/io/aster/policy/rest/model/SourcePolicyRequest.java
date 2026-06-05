package io.aster.policy.rest.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * CNL 源代码策略评估请求
 *
 * 支持直接传入 Aster CNL 源代码进行评估，无需提前部署。
 * 适用于 Dashboard 执行场景。
 *
 * @param source CNL 源代码
 * @param context 评估上下文参数（Map 或 Object 数组）
 * @param locale CNL 语言 (可选，默认 en-US)
 * @param functionName 要执行的函数名 (可选，默认 evaluate)
 */
public record SourcePolicyRequest(
    // 见 CnlSourceLimits：CNL 解析在长输入上超线性耗时，无上限即 DoS 向量。
    @NotNull(message = "source 不能为空")
    @Size(max = CnlSourceLimits.MAX_SOURCE_LENGTH,
          message = "源代码长度超过上限（最多 " + CnlSourceLimits.MAX_SOURCE_LENGTH + " 字符）")
    String source,

    @NotNull(message = "context 不能为空")
    Object context,

    String locale,

    String functionName
) {
    /**
     * 获取语言配置，默认 en-US
     */
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale;
    }

    /**
     * 获取函数名，默认 evaluate
     */
    public String getFunctionNameOrDefault() {
        return functionName == null || functionName.isBlank() ? "evaluate" : functionName;
    }
}
