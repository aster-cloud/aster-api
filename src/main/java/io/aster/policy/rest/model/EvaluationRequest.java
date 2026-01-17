package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * REST API请求：策略评估
 *
 * 用于单个策略的评估请求，包含策略模块、函数名和上下文数据。
 */
public record EvaluationRequest(
    @NotBlank(message = "policyModule不能为空")
    @JsonProperty("policyModule")
    String policyModule,

    @NotBlank(message = "policyFunction不能为空")
    @JsonProperty("policyFunction")
    String policyFunction,

    @NotNull(message = "context不能为null")
    @JsonProperty("context")
    Object[] context
) {
}
