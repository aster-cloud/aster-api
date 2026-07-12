package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * REST API 请求：编译 CNL 源代码（只解析+降级，不执行、不落库）。
 *
 * 供保存前的可编译性校验（cloud defense-in-depth）与 IDE compile-on-type 使用。
 * 与 {@code SchemaRequest} 同为**匿名**端点请求，故沿用更严的匿名源码上限
 * （{@link CnlSourceLimits#MAX_ANON_SOURCE_LENGTH}，16 KiB），把最坏单次解析
 * 耗时压进秒级、防算法复杂度 DoS。
 */
public record CompileRequest(
    @NotBlank(message = "源代码不能为空")
    @Size(max = CnlSourceLimits.MAX_ANON_SOURCE_LENGTH,
          message = "源代码长度超过上限（最多 " + CnlSourceLimits.MAX_ANON_SOURCE_LENGTH + " 字符）")
    @JsonProperty("source")
    String source,

    @JsonProperty("locale")
    String locale,

    // 用户自定义关键词别名（kind-name → 短语列表），可选。与执行/schema 一致：
    // 依赖别名的源码必须带 aliasSet 编译，否则会被误判为解析错误（前后端语义分裂）。
    @JsonProperty("aliasSet")
    Map<String, List<String>> aliasSet
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "en-US" : locale.trim();
    }
}
