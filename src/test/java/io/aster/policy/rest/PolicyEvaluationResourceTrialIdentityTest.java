package io.aster.policy.rest;

import io.aster.billing.ApiQuotaGuard;
import io.aster.policy.tenant.TenantContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R29 Codex 复审钉：trial 身份契约必须在 resource 层有直接回归覆盖，
 * 不只靠 podman E2E。本测试用反射访问 private 辅助方法，验证：
 *
 * <ul>
 *   <li>{@code tenantId()} 优先读 {@code TenantContext}，trial 流量返回 "trial"。</li>
 *   <li>{@code performedBy()} 对 trial 流量返回 "trial-anonymous"。</li>
 *   <li>{@code enforceApiQuota()} 对 tenant="trial" 不调用 {@code apiQuotaGuard.check()}，
 *       不写 {@code X-Quota-*} 响应头。</li>
 *   <li>普通租户走原路径：调用 quota guard 并写头。</li>
 * </ul>
 */
class PolicyEvaluationResourceTrialIdentityTest {

    private PolicyEvaluationResource newResource(ApiQuotaGuard quotaGuard,
                                                  TenantContext tenantContext,
                                                  RoutingContext routingContext) throws Exception {
        PolicyEvaluationResource r = new PolicyEvaluationResource();

        setField(r, "apiQuotaGuard", quotaGuard);
        setField(r, "tenantContext", tenantContext);
        setField(r, "routingContext", routingContext);

        return r;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = PolicyEvaluationResource.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] sig, Object... args)
        throws Exception {
        Method m = PolicyEvaluationResource.class.getDeclaredMethod(methodName, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test
    @DisplayName("R29: tenantId() 读 TenantContext.trial，跳过 header fallback")
    void tenantIdPrefersContextOverHeader() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        // Header 故意装一个错的值，证明 context 优先生效
        when(req.getHeader("X-Tenant-Id")).thenReturn("default");

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        String tenant = (String) invoke(r, "tenantId", new Class<?>[]{});
        assertEquals("trial", tenant,
            "TenantContext 设为 trial 时必须返回 trial，不读 header");
    }

    @Test
    @DisplayName("R29: performedBy() 对 trial 流量返回 trial-anonymous")
    void performedByReturnsTrialAnonymousForTrialTenant() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(rctx.request()).thenReturn(req);
        when(req.getHeader("X-User-Id")).thenReturn(null);

        PolicyEvaluationResource r = newResource(mock(ApiQuotaGuard.class), tc, rctx);

        String user = (String) invoke(r, "performedBy", new Class<?>[]{});
        assertEquals("trial-anonymous", user);
    }

    @Test
    @DisplayName("R29: enforceApiQuota() 对 trial 不调用 PlanGate，不写 X-Quota 头")
    void enforceApiQuotaSkipsForTrialTenant() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("trial");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        PolicyEvaluationResource r = newResource(quota, tc, rctx);

        ApiQuotaGuard.GuardResult result = (ApiQuotaGuard.GuardResult)
            invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
                "/api/v1/policies/evaluate-source");

        // 1. quota guard never invoked
        verify(quota, never()).check(anyString(), anyString());
        verify(quota, never()).checkRate(anyString(), anyString());

        // 2. no X-Quota-* headers written
        verify(resp, never()).putHeader(eqMatch("X-Quota-Limit"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Remaining"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Reset"), anyString());
        verify(resp, never()).putHeader(eqMatch("X-Quota-Warning"), anyString());

        // 3. returned verdict is ALLOW with -1 (unlimited)
        assertEquals(ApiQuotaGuard.Verdict.ALLOW, result.verdict());
        assertEquals(-1L, result.limit());
    }

    @Test
    @DisplayName("R29: 普通租户走原路径 —— PlanGate 调用 + X-Quota 头写入")
    void normalTenantStillEnforcesQuota() throws Exception {
        TenantContext tc = new TenantContext();
        tc.setCurrentTenant("acme-corp");

        RoutingContext rctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        HttpServerResponse resp = mock(HttpServerResponse.class);
        when(rctx.request()).thenReturn(req);
        when(rctx.response()).thenReturn(resp);
        when(req.getHeader("X-User-Id")).thenReturn("ops@acme");

        ApiQuotaGuard quota = mock(ApiQuotaGuard.class);
        when(quota.check(anyString(), anyString())).thenReturn(
            new ApiQuotaGuard.GuardResult(
                ApiQuotaGuard.Verdict.ALLOW, 10_000L, 100L, 1, null));

        PolicyEvaluationResource r = newResource(quota, tc, rctx);

        ApiQuotaGuard.GuardResult result = (ApiQuotaGuard.GuardResult)
            invoke(r, "enforceApiQuota", new Class<?>[]{String.class},
                "/api/v1/policies/evaluate");

        verify(quota, times(1)).check("acme-corp", "ops@acme");
        verify(resp).putHeader(eqMatch("X-Quota-Limit"), anyString());
        verify(resp).putHeader(eqMatch("X-Quota-Remaining"), anyString());
        verify(resp).putHeader(eqMatch("X-Quota-Reset"), anyString());

        assertEquals(ApiQuotaGuard.Verdict.ALLOW, result.verdict());
    }

    // Mockito eq helper for String — keeps imports tight in this test
    private static String eqMatch(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
