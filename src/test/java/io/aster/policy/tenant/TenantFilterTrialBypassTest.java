package io.aster.policy.tenant;

import io.aster.policy.security.TrialEndpointGuard;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R28: 端到端测试发现 TenantFilter 在 trial 路径上要求 X-Tenant-Id，
 * 而匿名访客不可能持有租户 ID，导致 trial 流被 400 拒绝。修复后：
 * 当 TrialEndpointGuard 已经放行（设置 TRIAL_GUARD_PASSED_PROP），
 * TenantFilter 用伪租户 "trial" 跳过 header 校验。
 *
 * <p>这个回归测试钉住该行为，与 RoleEnforcementFilterTrialBypassTest 配套。
 */
class TenantFilterTrialBypassTest {

    private TenantFilter newFilter() throws Exception {
        TenantFilter filter = new TenantFilter();
        Field f1 = TenantFilter.class.getDeclaredField("whitelistEnabled");
        f1.setAccessible(true);
        f1.set(filter, false);
        Field f2 = TenantFilter.class.getDeclaredField("allowedTenants");
        f2.setAccessible(true);
        f2.set(filter, Optional.<String>empty());
        Field f3 = TenantFilter.class.getDeclaredField("strictFormat");
        f3.setAccessible(true);
        f3.set(filter, true);
        Field f4 = TenantFilter.class.getDeclaredField("tenantContext");
        f4.setAccessible(true);
        f4.set(filter, new TenantContext());
        return filter;
    }

    private ContainerRequestContext newCtx(String method,
                                           String path,
                                           Map<String, String> headers,
                                           Map<String, Object> properties) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn(method);

        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(ctx.getUriInfo()).thenReturn(uri);

        MultivaluedMap<String, String> hdr = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) hdr.add(e.getKey(), e.getValue());
        when(ctx.getHeaders()).thenReturn(hdr);
        for (var e : headers.entrySet()) {
            when(ctx.getHeaderString(e.getKey())).thenReturn(e.getValue());
        }
        for (var e : properties.entrySet()) {
            when(ctx.getProperty(e.getKey())).thenReturn(e.getValue());
        }
        return ctx;
    }

    @Test
    @DisplayName("R28: trial guard 凭证 → 跳过 X-Tenant-Id 校验，伪租户=trial")
    void trialGuardPropertyBypassesTenantHeader() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate-source",
            Map.of(),  // 没有 X-Tenant-Id
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("R28: 普通路径仍要求 X-Tenant-Id，trial 凭证保护范围严格")
    void normalPathStillRequiresTenant() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",
            Map.of(),   // 既无 X-Tenant-Id，也无 trial 凭证
            Map.of()
        );

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(400, resp.getValue().getStatus(),
            "缺 X-Tenant-Id 应当返回 400");
    }

    @Test
    @DisplayName("R28: OPTIONS 预检始终放行")
    void optionsPreflightSkipped() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx(
            "OPTIONS",
            "/api/v1/policies/evaluate",
            Map.of(),
            Map.of()
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("R28: 正常租户用户带合法 X-Tenant-Id 通过")
    void validTenantPasses() throws Exception {
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",
            Map.of("X-Tenant-Id", "acme-corp"),
            Map.of()
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("R29: trial 凭证落在非 trial 路径 → 不旁路，仍走原 X-Tenant-Id 校验")
    void trialPropertyOnNonTrialPathDoesNotBypass() throws Exception {
        // Codex R28 audit 指出：TenantFilter 不复查路径会出现弱耦合。
        // 修复后即便有 property，路径不是 TRIAL_PATH 就回到普通校验。
        TenantFilter filter = newFilter();
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",  // 注意：不是 evaluate-source
            Map.of(),                     // 无 X-Tenant-Id
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        Assertions.assertEquals(400, resp.getValue().getStatus(),
            "guard property 不应该让 trial 路径外的请求拿到伪租户");
    }
}
