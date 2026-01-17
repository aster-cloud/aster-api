package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * REST API请求：获取策略参数模式
 *
 * 提供 CNL 源代码，返回函数参数的结构化模式信息。
 */
public record SchemaRequest(
    @NotBlank(message = "源代码不能为空")
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
