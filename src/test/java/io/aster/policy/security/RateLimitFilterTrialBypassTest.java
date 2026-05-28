package io.aster.policy.security;

import io.aster.policy.tenant.TenantContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R29 Codex audit 发现：RateLimitFilter 优先级 {@code AUTHENTICATION+1}
 * 会在 JAX-RS TenantFilter 之前执行，对 trial 路径而言 TenantContext 还没
 * 初始化，落到 {@code rest:ip:unknown} 全局 60/min 桶，叠加
 * TrialEndpointGuard 已有的 per-IP 限流形成双重限流且互相挤桶。
 *
 * <p>修复：识别 {@link TrialEndpointGuard#TRIAL_GUARD_PASSED_PROP} +
 * {@link TrialEndpointGuard#TRIAL_PATH} 后直接 return null 放行。
 * 本测试钉住该 bypass。
 */
class RateLimitFilterTrialBypassTest {

    private RateLimitFilter newFilter(boolean enabled) throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        Field f = RateLimitFilter.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(filter, enabled);

        Field maxReq = RateLimitFilter.class.getDeclaredField("maxRequests");
        maxReq.setAccessible(true);
        maxReq.setInt(filter, 60);
        Field win = RateLimitFilter.class.getDeclaredField("windowSeconds");
        win.setAccessible(true);
        win.setInt(filter, 60);

        // tenantContext is referenced for the non-bypass path; trial bypass
        // returns before touching it, but @Inject still expects a non-null.
        Field tc = RateLimitFilter.class.getDeclaredField("tenantContext");
        tc.setAccessible(true);
        tc.set(filter, new TenantContext());
        return filter;
    }

    private ContainerRequestContext newCtx(String path,
                                           Map<String, Object> properties) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");

        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(ctx.getUriInfo()).thenReturn(uri);

        MultivaluedMap<String, String> hdr = new MultivaluedHashMap<>();
        when(ctx.getHeaders()).thenReturn(hdr);

        for (var e : properties.entrySet()) {
            when(ctx.getProperty(e.getKey())).thenReturn(e.getValue());
        }
        return ctx;
    }

    @Test
    @DisplayName("R29: trial guard 凭证 + TRIAL_PATH → 跳过全局 REST 限流")
    void trialPathWithGuardPropertyBypassesRateLimiter() throws Exception {
        RateLimitFilter filter = newFilter(true);
        ContainerRequestContext ctx = newCtx(
            "/api/v1/policies/evaluate-source",
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        Response result = filter.filter(ctx);

        assertNull(result,
            "trial 路径放行：filter 应返回 null，不阻断 chain");
    }

    @Test
    @DisplayName("R29: trial 凭证但非 trial 路径 → 不旁路（继续走限流逻辑）")
    void trialPropertyOnNonTrialPathDoesNotBypass() throws Exception {
        // 与 RBAC + Tenant 一致的纵深防御：property + 路径双重校验。
        // 这里通过观察 filter 是否进入到下游依赖（RateLimiter）来反证
        // 短路未命中：rateLimiter 未注入 → NPE 抛出，说明 filter 没有
        // 在 trial 短路里早返回。如果将来把短路改回只看 property，
        // 该测试会无 NPE 通过 = 失败。
        RateLimitFilter filter = newFilter(true);
        ContainerRequestContext ctx = newCtx(
            "/api/v1/policies/evaluate",  // 注意：不是 evaluate-source
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        assertThrows(NullPointerException.class, () -> filter.filter(ctx),
            "非 trial 路径必须走完限流链路，触达 rateLimiter 依赖");
    }

    @Test
    @DisplayName("R29: /q/* 仍然豁免（管理端点）")
    void healthPathStillExempt() throws Exception {
        RateLimitFilter filter = newFilter(true);
        ContainerRequestContext ctx = newCtx("/q/health", Map.of());

        Response result = filter.filter(ctx);

        assertNull(result, "/q/* 必须放行（健康检查）");
    }

    @Test
    @DisplayName("R29: enabled=false 时整个 filter 不做事")
    void disabledFilterSkipsEverything() throws Exception {
        RateLimitFilter filter = newFilter(false);
        ContainerRequestContext ctx = newCtx("/api/v1/policies/evaluate", Map.of());

        Response result = filter.filter(ctx);

        assertNull(result);
    }
}
