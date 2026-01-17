package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API响应：清除缓存结果
 *
 * 返回缓存清除操作的成功状态。
 */
public record CacheClearResponse(
    @JsonProperty("success")
    boolean success,

    @JsonProperty("message")
    String message
) {
    public static CacheClearResponse success(String message) {
        return new CacheClearResponse(true, message);
    }

    public static CacheClearResponse failure(String message) {
        return new CacheClearResponse(false, message);
    }
}
