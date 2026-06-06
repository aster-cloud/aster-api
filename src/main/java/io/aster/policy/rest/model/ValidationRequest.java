package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * REST API请求：策略验证
 *
 * 用于验证策略模块和函数是否存在且可调用。
 */
public record ValidationRequest(
    // 标识符字段限长：会进入 service/cache key/日志，防超长路径式名字污染。
    @NotBlank(message = "policyModule不能为空")
    @Size(max = CnlSourceLimits.MAX_IDENTIFIER_LENGTH, message = "policyModule 过长")
    @JsonProperty("policyModule")
    String policyModule,

    @NotBlank(message = "policyFunction不能为空")
    @Size(max = CnlSourceLimits.MAX_IDENTIFIER_LENGTH, message = "policyFunction 过长")
    @JsonProperty("policyFunction")
    String policyFunction
) {
}
