package io.aster.billing;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * PlanLookupUnavailableException → HTTP 503 Service Unavailable + 标准化 JSON。
 *
 * <p>与 {@link PlanLimitExceptionMapper}（402，升级）严格区分：plan lookup
 * 不可用是临时性服务问题，客户端应重试，而不是被引导去升级套餐。附带
 * Retry-After 提示调用方稍后再试。
 */
@Provider
public class PlanLookupUnavailableExceptionMapper
        implements ExceptionMapper<PlanLookupUnavailableException> {

    @Override
    public Response toResponse(PlanLookupUnavailableException ex) {
        Map<String, Object> body = Map.of(
            "error", "plan_lookup_unavailable",
            "reason", ex.reason(),
            "retryable", true,
            "message", "Plan information is temporarily unavailable; please retry."
        );

        return Response.status(503)
            .header("Retry-After", "2")
            .type(MediaType.APPLICATION_JSON)
            .entity(body)
            .build();
    }
}
