package io.aster.policy.security.rbac;

import io.aster.policy.security.TrialEndpointGuard;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R28 end-to-end test 在 podman 中发现：marketing-tier trial 流被 RBAC 误判
 * 为 "Missing role information"。匿名访客不可能持有 {@code X-User-Role}，
 * 而 trial 端点恰恰是设计给匿名访客用的。
 *
 * <p>修复：当 TrialEndpointGuard 已经在更早的 filter 链中把
 * {@link TrialEndpointGuard#TRIAL_GUARD_PASSED_PROP} 置为 {@code true}，
 * 且当前请求路径正好是 {@link TrialEndpointGuard#TRIAL_PATH} 时，跳过 RBAC。
 *
 * <p>本测试钉住这一行为，防止任何重构静默关掉该 bypass。
 */
class RoleEnforcementFilterTrialBypassTest {

    private RoleEnforcementFilter newFilter(boolean rbacEnabled,
                                            Class<?> resourceClass) throws Exception {
        RoleEnforcementFilter filter = new RoleEnforcementFilter();
        // ConfigProperty 在单测里没注入，反射置入
        Field f = RoleEnforcementFilter.class.getDeclaredField("rbacEnabled");
        f.setAccessible(true);
        f.set(filter, rbacEnabled);

        ResourceInfo info = mock(ResourceInfo.class);
        doReturn(resourceClass).when(info).getResourceClass();
        when(info.getResourceMethod()).thenReturn(null);

        Field rf = RoleEnforcementFilter.class.getDeclaredField("resourceInfo");
        rf.setAccessible(true);
        rf.set(filter, info);
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

    @RequireRole(Role.MEMBER)
    static class FakeMemberResource {}

    @RequireRole(Role.ADMIN)
    static class FakeAdminResource {}

    @Test
    @DisplayName("R28: trial 路径 + 已通过 guard → 跳过 RBAC 不要求角色 header")
    void trialPathWithGuardPassedSkipsRbac() throws Exception {
        RoleEnforcementFilter filter = newFilter(true, FakeMemberResource.class);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate-source",
            Map.of(),  // 没有 X-User-Role —— 模拟匿名 trial 访客
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("R28: trial 路径但 guard 凭证缺失 → 仍要求 X-User-Role")
    void trialPathWithoutGuardPropStillRequiresRole() throws Exception {
        RoleEnforcementFilter filter = newFilter(true, FakeMemberResource.class);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate-source",
            Map.of(),  // 既无 X-User-Role 又无 guard 凭证
            Map.of()
        );

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(403, resp.getValue().getStatus(),
            "缺 guard 凭证时不允许走 trial bypass，仍要 RBAC");
    }

    @Test
    @DisplayName("R28: 非 trial 路径即便带 guard 凭证也不绕过 RBAC")
    void nonTrialPathDoesNotBypassEvenWithProperty() throws Exception {
        RoleEnforcementFilter filter = newFilter(true, FakeAdminResource.class);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",  // 注意：不是 evaluate-source
            Map.of(),
            // 即便恶意客户端构造了 property，路径错了也不放
            Map.of(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP, Boolean.TRUE)
        );

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(403, resp.getValue().getStatus(),
            "guard 凭证只对 evaluate-source 一条路径生效");
    }

    @Test
    @DisplayName("R28: OPTIONS 预检请求始终放行（CORS 必要条件）")
    void optionsPreflightAlwaysSkipsRbac() throws Exception {
        RoleEnforcementFilter filter = newFilter(true, FakeMemberResource.class);
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
    @DisplayName("R28: rbac.enabled=false 时整个 filter 不做任何事")
    void rbacDisabledSkipsEverything() throws Exception {
        RoleEnforcementFilter filter = newFilter(false, FakeMemberResource.class);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",
            Map.of(),
            Map.of()
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("R28: 正常租户用户带 X-User-Role 通过 RBAC")
    void validRoleHeaderPassesRbac() throws Exception {
        RoleEnforcementFilter filter = newFilter(true, FakeMemberResource.class);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",
            Map.of("X-User-Role", "MEMBER"),
            Map.of()
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }
}
