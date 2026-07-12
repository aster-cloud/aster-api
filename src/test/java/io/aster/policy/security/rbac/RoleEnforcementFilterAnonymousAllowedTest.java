package io.aster.policy.security.rbac;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住 {@link AnonymousAllowed} 的语义：方法级标注后，即使所在类带类级
 * {@link RequireRole}，{@link RoleEnforcementFilter} 也跳过 RBAC，对未认证
 * 调用方开放。
 *
 * <p>背景：/schema、/validate 是只读元数据端点（无副作用、不写数据、自带 @Size
 * + 并发闸 + 限流），设计上必须匿名；但它们所在的 PolicyEvaluationResource 带
 * 类级 @RequireRole(MEMBER)，导致此前匿名调用被误判 403 "Missing role
 * information"。本测试防止该豁免被重构静默关掉。
 */
class RoleEnforcementFilterAnonymousAllowedTest {

    @RequireRole(Role.MEMBER)
    static class FakeProtectedResource {
        @AnonymousAllowed
        public void anonymousMethod() {}

        public void protectedMethod() {}
    }

    private RoleEnforcementFilter newFilter(Method resourceMethod) throws Exception {
        RoleEnforcementFilter filter = new RoleEnforcementFilter();
        Field f = RoleEnforcementFilter.class.getDeclaredField("rbacEnabled");
        f.setAccessible(true);
        f.set(filter, true);

        ResourceInfo info = mock(ResourceInfo.class);
        doReturn(FakeProtectedResource.class).when(info).getResourceClass();
        doReturn(resourceMethod).when(info).getResourceMethod();

        Field rf = RoleEnforcementFilter.class.getDeclaredField("resourceInfo");
        rf.setAccessible(true);
        rf.set(filter, info);
        return filter;
    }

    private ContainerRequestContext newCtx(String method, String path, Map<String, String> headers) {
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
        return ctx;
    }

    @Test
    @DisplayName("方法标 @AnonymousAllowed + 类级 @RequireRole + 无 X-User-Role → 跳过 RBAC 不 abort")
    void anonymousAllowedMethodSkipsRbacDespiteClassRequireRole() throws Exception {
        Method m = FakeProtectedResource.class.getMethod("anonymousMethod");
        RoleEnforcementFilter filter = newFilter(m);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/schema",
            Map.of()  // 匿名：无 X-User-Role
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("同类未标 @AnonymousAllowed 的方法 + 无 X-User-Role → 仍按类级 @RequireRole 403")
    void nonAnnotatedMethodStillEnforcesClassRequireRole() throws Exception {
        Method m = FakeProtectedResource.class.getMethod("protectedMethod");
        RoleEnforcementFilter filter = newFilter(m);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/evaluate",
            Map.of()  // 无 X-User-Role
        );

        filter.filter(ctx);

        ArgumentCaptor<Response> resp = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(resp.capture());
        assertEquals(403, resp.getValue().getStatus(),
            "未标 @AnonymousAllowed 的方法仍受类级 @RequireRole 保护");
    }

    @Test
    @DisplayName("@AnonymousAllowed 方法即便带伪造的 X-User-Role 也直接放行（不解析、不校验）")
    void anonymousAllowedIgnoresForgedRoleHeader() throws Exception {
        Method m = FakeProtectedResource.class.getMethod("anonymousMethod");
        RoleEnforcementFilter filter = newFilter(m);
        ContainerRequestContext ctx = newCtx(
            "POST",
            "/api/v1/policies/schema",
            Map.of("X-User-Role", "BOGUS_NOT_A_ROLE")  // 即使是非法值也不应触发 Invalid role 分支
        );

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    /**
     * 允许列表回归：@AnonymousAllowed 是绕过 RBAC 的口子，必须严格限定在只读元数据
     * 端点上。本测试钉死当前代码库里允许匿名的方法集合——若未来有人把它随手标到写/
     * 突变端点（evaluate、rollback、cache 等），这里会失败，强制重新评审。
     */
    @Test
    @DisplayName("允许列表：PolicyEvaluationResource 仅 getSchema/validate/compile 可标 @AnonymousAllowed")
    void onlyMetadataEndpointsAreAnonymousAllowed() {
        // compile：只读源码编译（只解析+降级，不执行、不落库、无副作用），与
        // schema/validate 同性质。防护靠 @Size 16KB 匿名上限 + ANON_PARSE_PERMITS
        // 并发闸 + 限流。经安全评审加入白名单（供保存前校验 + IDE compile-on-type）。
        java.util.Set<String> expected = java.util.Set.of("getSchema", "validate", "compile");
        java.util.Set<String> actual = new java.util.TreeSet<>();
        for (Method m : io.aster.policy.rest.PolicyEvaluationResource.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(AnonymousAllowed.class)) {
                actual.add(m.getName());
            }
        }
        assertEquals(expected, actual,
            "只有只读元数据端点(getSchema/validate)允许匿名；新增 @AnonymousAllowed 必须经安全评审并更新本断言");
    }
}
