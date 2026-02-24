package io.aster.policy.exception;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * 统一处理所有未捕获的异常，返回一致的错误响应格式
 * 生产环境隐藏内部错误详情，仅返回错误码
 */
@Provider
@RegisterForReflection
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @ConfigProperty(name = "aster.error.include-details", defaultValue = "false")
    boolean includeDetails;

    /**
     * 标准错误响应格式
     */
    @RegisterForReflection
    public record ErrorResponse(
        String errorCode,
        String message,
        String traceId,
        Instant timestamp,
        Map<String, Object> details
    ) {}

    @Override
    public Response toResponse(Throwable exception) {
        SpanContext spanCtx = Span.current().getSpanContext();
        String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : java.util.UUID.randomUUID().toString();

        // 记录异常日志
        LOG.errorf(exception, "请求处理失败 [traceId=%s]: %s", traceId, exception.getMessage());

        // 处理不同类型的异常
        if (exception instanceof AsterPolicyException asterEx) {
            return handleAsterException(asterEx, traceId);
        } else if (exception instanceof WebApplicationException webEx) {
            return handleWebApplicationException(webEx, traceId);
        } else {
            return handleUnknownException(exception, traceId);
        }
    }

    /**
     * 处理 Aster 业务异常
     */
    private Response handleAsterException(AsterPolicyException exception, String traceId) {
        int statusCode = determineStatusCode(exception);

        ErrorResponse errorResponse = new ErrorResponse(
            exception.getErrorCode(),
            exception.getMessage(),
            traceId,
            Instant.now(),
            buildDetails(exception)
        );

        return Response.status(statusCode)
            .entity(errorResponse)
            .build();
    }

    /**
     * 处理 JAX-RS 异常
     */
    private Response handleWebApplicationException(WebApplicationException exception, String traceId) {
        Response originalResponse = exception.getResponse();

        ErrorResponse errorResponse = new ErrorResponse(
            "HTTP_" + originalResponse.getStatus(),
            shouldExposeMessage(originalResponse.getStatus())
                ? exception.getMessage()
                : "请求处理失败",
            traceId,
            Instant.now(),
            null
        );

        return Response.status(originalResponse.getStatus())
            .entity(errorResponse)
            .build();
    }

    /**
     * 处理未知异常
     */
    private Response handleUnknownException(Throwable exception, String traceId) {
        // 生产环境不暴露内部错误详情
        String message = isDevMode()
            ? exception.getMessage()
            : "服务器内部错误，请稍后重试";

        ErrorResponse errorResponse = new ErrorResponse(
            "INTERNAL_ERROR",
            message,
            traceId,
            Instant.now(),
            isDevMode() ? Map.of("exceptionType", exception.getClass().getName()) : null
        );

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(errorResponse)
            .build();
    }

    /**
     * 根据异常类型确定 HTTP 状态码
     */
    private int determineStatusCode(AsterPolicyException exception) {
        return switch (exception) {
            case PolicyNotFoundException ignored -> 404;  // NOT_FOUND
            case ValidationException ignored -> 400;       // BAD_REQUEST
            case SecurityException ignored -> 401;         // UNAUTHORIZED
            case PolicyEvaluationException ignored -> 422; // UNPROCESSABLE_ENTITY
            default -> 500;                                // INTERNAL_SERVER_ERROR
        };
    }

    /**
     * 构建错误详情（仅开发模式或显式启用时）
     */
    private Map<String, Object> buildDetails(AsterPolicyException exception) {
        if (!isDevMode() && !includeDetails) {
            return null;
        }

        return switch (exception) {
            case PolicyEvaluationException ex -> Map.of(
                "policyName", ex.getPolicyName() != null ? ex.getPolicyName() : "",
                "policyVersion", ex.getPolicyVersion() != null ? ex.getPolicyVersion() : ""
            );
            case PolicyNotFoundException ex -> Map.of(
                "policyId", ex.getPolicyId() != null ? ex.getPolicyId() : ""
            );
            case ValidationException ex -> Map.of(
                "validationErrors", ex.getValidationErrors()
            );
            case SecurityException ex -> Map.of(
                "eventType", ex.getEventType() != null ? ex.getEventType() : ""
            );
            default -> null;
        };
    }

    /**
     * 判断是否应该暴露错误消息
     */
    private boolean shouldExposeMessage(int status) {
        // 4xx 客户端错误可以暴露消息
        return status >= 400 && status < 500;
    }

    /**
     * 判断是否为开发模式
     */
    private boolean isDevMode() {
        return "dev".equals(profile) || "test".equals(profile);
    }
}
