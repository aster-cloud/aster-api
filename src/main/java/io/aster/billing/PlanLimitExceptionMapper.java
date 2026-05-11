package io.aster.billing;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * PlanLimitException → HTTP 402 Payment Required + 标准化 JSON
 *
 * 响应体与前端 UpgradeBlocker 约定的格式对齐：
 * { "upgrade": true, "reason": "reviewer_required", "recommendedPlan": "pro" }
 */
@Provider
public class PlanLimitExceptionMapper implements ExceptionMapper<PlanLimitException> {

    @Override
    public Response toResponse(PlanLimitException ex) {
        String reason = ex.reason();
        String recommended = "sso".equals(reason)
            || "data_residency".equals(reason)
            || "audit_retention".equals(reason)
            ? "enterprise"
            : "pro";

        Map<String, Object> body = Map.of(
            "upgrade", true,
            "reason", reason,
            "recommendedPlan", recommended,
            "message", "upgrade required: " + reason
        );

        return Response.status(402)
            .type(MediaType.APPLICATION_JSON)
            .entity(body)
            .build();
    }
}
