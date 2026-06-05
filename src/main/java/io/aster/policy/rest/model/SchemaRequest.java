package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * REST API请求：获取策略参数模式
 *
 * 提供 CNL 源代码，返回函数参数的结构化模式信息。
 */
public record SchemaRequest(
    // 源码长度硬上限：CNL 解析/规范化在长输入上呈超线性耗时（实测 10KB≈1.5s，
    // 50KB 已 >15s），无上限时是算法复杂度 DoS 向量——少量大 body 即可耗尽
    // worker 线程池。合法策略普遍 < 几 KB，64KB 上限对真实用例绰绰有余，
    // 同时把恶意/异常大输入在校验层快速 400 拒绝，不进入昂贵的解析路径。
    @NotBlank(message = "源代码不能为空")
    @Size(max = CnlSourceLimits.MAX_SOURCE_LENGTH,
          message = "源代码长度超过上限（最多 " + CnlSourceLimits.MAX_SOURCE_LENGTH + " 字符）")
    @JsonProperty("source")
    String source,

    @JsonProperty("functionName")
    String functionName,

    @JsonProperty("locale")
    String locale
) {
    /**
     * 获取语言代码（默认 en-US）
     */
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale.trim();
    }

    /**
     * 获取函数名（可空，由服务端决定使用第一个函数）
     */
    public String getFunctionNameOrDefault() {
        return functionName == null || functionName.isBlank() ? null : functionName.trim();
    }
}
