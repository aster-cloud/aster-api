package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * REST API请求：清除策略缓存
 *
 * 用于清除特定租户、策略模块和函数的缓存。
 * 所有字段都是可选的，用于精确控制缓存失效范围。
 */
public record CacheClearRequest(
    @Size(max = CnlSourceLimits.MAX_IDENTIFIER_LENGTH, message = "policyModule 过长")
    @JsonProperty("policyModule")
    String policyModule,

    @Size(max = CnlSourceLimits.MAX_IDENTIFIER_LENGTH, message = "policyFunction 过长")
    @JsonProperty("policyFunction")
    String policyFunction
) {
}
