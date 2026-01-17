package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * REST API请求：策略验证
 *
 * 用于验证策略模块和函数是否存在且可调用。
 */
public record ValidationRequest(
    @NotBlank(message = "policyModule不能为空")
    @JsonProperty("policyModule")
    String policyModule,

    @NotBlank(message = "policyFunction不能为空")
    @JsonProperty("policyFunction")
    String policyFunction
) {
}
