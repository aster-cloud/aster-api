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

    /** 读取 socket 远端地址（trust-forwarded-headers=false 时的 IP 来源）。 */
    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * 是否信任转发头作为客户端真实 IP（解析优先级见 {@link TrialEndpointGuard#clientIp}：
     * CF-Connecting-IP &gt; XFF 首段 &gt; X-Real-IP &gt; socket）。
     *
     * <p>默认 false：用 socket 源地址，防止客户端轮换 XFF/CF 头绕过匿名限流、制造
     * 高基数 key 撑爆限流 map。置 true 仅适用于：(a) Cloudflare Tunnel-only 拓扑
     * （CF-Connecting-IP 边缘写入、不可伪造）；或 (b) 会覆盖 XFF 且清洗客户端 CF 头
     * 的可信反代/ingress。语义与 {@code aster.security.trial.trust-forwarded-for}
     * 一致（部署需同时打开），复用 TrialEndpointGuard.clientIp 解析。
     */
    @ConfigProperty(name = "aster.ratelimit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    @ConfigProperty(name = "aster.ratelimit.rest.max-requests", defaultValue = "60")
    int maxRequests;

    @ConfigProperty(name = "aster.ratelimit.rest.window-seconds", defaultValue = "60")
    int windowSeconds;

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 1)
    public Response filter(ContainerRequestContext ctx) {
        // RESTEasy Reactive's @ServerRequestFilter requires returning the
        // Response (or Uni<Response>) for short-circuiting. Calling
        // ctx.abortWith from inside such a filter throws IllegalStateException
        // and bubbles out as a 500 — which is exactly what k6 saw as
        // "100% 5xx error rate" when the rate limiter triggered.
        //
        // Returning null means "continue down the chain".
        if (!enabled) {
            return null;
        }

        String path = ctx.getUriInfo().getPath();

        // 豁免管理端点和内部端点
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")) {
            return null;
        }

        // R29→R30 trial 旁路：TrialEndpointGuard 已经提供 per-IP minute/hour/
        // concurrent 三层成本控制，叠加本 REST limiter 既冗余又会因为 filter
        // 优先级早于 TenantFilter 导致 tenantContext 未 set，落到
        // rest:ip:unknown 全局桶。统一用 TrialBypassPredicate 判定。
        if (TrialBypassPredicate.isGuardedTrialRequest(ctx)) {
            LOG.debugf("rate limit bypassed for guarded trial path %s", path);
            return null;
        }

        // 限流 key：优先租户，否则回退客户端 IP。
        // 注意：本 filter 在 AUTHENTICATION+1 执行，**早于** TenantFilter，所以
        // 绝大多数请求此刻 TenantContext 尚未初始化，会走 IP 分支——这正是
        // trust-forwarded-headers 在生产必须正确配置的原因（见 application.
        // properties 注释：反代后须置 true 且覆盖 XFF，否则全站折叠到入口 IP）。
        // 已认证流量的精确按量计费由下游 ApiQuotaGuard（在 apikey 验证之后）承担；
        // 本 filter 是粗粒度的入口防滥用闸。
        String identifier;
        if (tenantContext.isInitialized()) {
            identifier = "rest:" + tenantContext.getCurrentTenant();
        } else {
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

            return Response.status(429)
                .header("Retry-After", String.valueOf(retryAfter))
                .header("X-RateLimit-Limit", String.valueOf(maxRequests))
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", String.valueOf(resetAt))
                .entity("{\"error\":\"Too Many Requests\",\"retryAfter\":" + retryAfter + "}")
                .build();
        }
        return null;
    }

    /**
     * 提取客户端 IP。复用 {@link TrialEndpointGuard#clientIp}：
     * trust-forwarded-headers=false（默认）时返回 socket 源地址，忽略可伪造的
     * XFF/CF 头；=true 时优先不可伪造的 CF-Connecting-IP，再 XFF 首段 / X-Real-IP。
     * 两处限流共享同一信任边界。
     */
    private String getClientIp(ContainerRequestContext ctx) {
        return TrialEndpointGuard.clientIp(ctx, routingContext, trustForwardedHeaders);
    }
}
