package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API响应：策略验证结果
 *
 * 返回策略是否有效及其元数据信息。
 */
public record ValidationResponse(
    @JsonProperty("valid")
    boolean valid,

    @JsonProperty("message")
    String message,

    @JsonProperty("parameterCount")
    Integer parameterCount,

    @JsonProperty("returnType")
    String returnType
) {
    public static ValidationResponse success(int parameterCount, String returnType) {
        return new ValidationResponse(true, "Policy is valid and callable", parameterCount, returnType);
    }

    public static ValidationResponse failure(String message) {
        return new ValidationResponse(false, message, null, null);
    }
}
