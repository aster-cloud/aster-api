package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import jakarta.ws.rs.container.ContainerRequestContext;
import java.time.Duration;

/**
 * REST API 限流过滤器
 *
 * 在 AUTHENTICATION 之后、AUTHORIZATION 之前执行，
 * 基于租户ID 进行滑动窗口限流。
 *
 * 豁免路径：/q/*（健康检查等管理端点）、/internal/*
 */
@ApplicationScoped
public class RateLimitFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);

    @Inject
    RateLimiter rateLimiter;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "aster.ratelimit.rest.max-requests", defaultValue = "60")
    int maxRequests;

    @ConfigProperty(name = "aster.ratelimit.rest.window-seconds", defaultValue = "60")
    int windowSeconds;

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 1)
    public void filter(ContainerRequestContext ctx) {
        if (!enabled) {
            return;
        }

        String path = ctx.getUriInfo().getPath();

        // 豁免管理端点和内部端点
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")) {
            return;
        }

        // 使用租户ID作为限流 key（TenantFilter 已在前置过滤器中校验并设置）
        String identifier;
        if (tenantContext.isInitialized()) {
            identifier = "rest:" + tenantContext.getCurrentTenant();
        } else {
            // 租户上下文未初始化（TenantFilter 尚未执行或路径被豁免），使用客户端 IP
            identifier = "rest:ip:" + getClientIp(ctx);
        }

        Duration window = Duration.ofSeconds(windowSeconds);
        boolean allowed = rateLimiter.tryAcquire(identifier, maxRequests, window);

        // 始终设置限流响应头
        int remaining = rateLimiter.remaining(identifier, maxRequests, window);
        long resetAt = rateLimiter.resetAtEpochSecond(identifier, window);

        ctx.getHeaders().putSingle("X-RateLimit-Limit", String.valueOf(maxRequests));
        ctx.getHeaders().putSingle("X-RateLimit-Remaining", String.valueOf(remaining));
        ctx.getHeaders().putSingle("X-RateLimit-Reset", String.valueOf(resetAt));

        if (!allowed) {
            long retryAfter = Math.max(1, resetAt - java.time.Instant.now().getEpochSecond());
            LOG.warnf("限流拒绝: identifier=%s, path=%s", identifier, path);

            ctx.abortWith(
                Response.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .header("X-RateLimit-Limit", String.valueOf(maxRequests))
                    .header("X-RateLimit-Remaining", "0")
                    .header("X-RateLimit-Reset", String.valueOf(resetAt))
                    .entity("{\"error\":\"Too Many Requests\",\"retryAfter\":" + retryAfter + "}")
                    .build()
            );
        }
    }

    /**
     * 从请求头中提取客户端 IP（支持代理场景）
     */
    private String getClientIp(ContainerRequestContext ctx) {
        String forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        String realIp = ctx.getHeaderString("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }

        return "unknown";
    }
}
