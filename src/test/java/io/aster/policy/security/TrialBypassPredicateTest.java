package io.aster.policy.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R30 集中判定 helper 的直接单元测试。5 个 bypass 调用点
 * （RBAC、Tenant、RateLimit、InternalCaller、enforceApiQuota）都委托
 * 给这个 helper —— 任何漂移都会被这里钉住。
 */
class TrialBypassPredicateTest {

    private ContainerRequestContext ctx(String path, Object prop) {
        ContainerRequestContext c = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + (path == null ? "" : path)));
        when(c.getUriInfo()).thenReturn(uri);
        if (prop != null) {
            when(c.getProperty(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP)).thenReturn(prop);
        }
        return c;
    }

    @Test
    @DisplayName("R30: TRIAL_PATH + property=true → true")
    void trialPathWithPropTrue() {
        assertTrue(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate-source", Boolean.TRUE)));
    }

    @Test
    @DisplayName("R30: 非 trial 路径 + property=true → false (路径校验)")
    void nonTrialPathWithPropTrue() {
        assertFalse(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate", Boolean.TRUE)));
    }

    @Test
    @DisplayName("R30: TRIAL_PATH + 缺 property → false (凭证校验)")
    void trialPathWithoutProp() {
        assertFalse(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate-source", null)));
    }

    @Test
    @DisplayName("R30: TRIAL_PATH + property=false → false")
    void trialPathWithPropFalse() {
        assertFalse(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate-source", Boolean.FALSE)));
    }

    @Test
    @DisplayName("R30: TRIAL_PATH + property=非Boolean → false (防伪 property)")
    void trialPathWithStringProp() {
        assertFalse(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate-source", "true")));
    }

    @Test
    @DisplayName("R30: null ctx → false (NPE 防御)")
    void nullCtx() {
        assertFalse(TrialBypassPredicate.isGuardedTrialRequest(null));
    }

    @Test
    @DisplayName("R30: matrix-param 视差 trial-path + property → 仍 true (PathNormalizer 归一)")
    void matrixParamPathIsNormalized() {
        // 防 matrix-param 攻击：trial-path;x=1 经过 PathNormalizer 后等于
        // TRIAL_PATH。这里只要 helper 接受归一化结果即通过。
        assertTrue(TrialBypassPredicate.isGuardedTrialRequest(
            ctx("/api/v1/policies/evaluate-source;x=1", Boolean.TRUE)));
    }

    @Test
    @DisplayName("R30: isGuardedTrialPath(String, Boolean) 非 JAX-RS 形式同样校验")
    void rawPathFormApi() {
        assertTrue(TrialBypassPredicate.isGuardedTrialPath(
            "/api/v1/policies/evaluate-source", Boolean.TRUE));
        assertFalse(TrialBypassPredicate.isGuardedTrialPath(
            "/api/v1/policies/evaluate", Boolean.TRUE));
        assertFalse(TrialBypassPredicate.isGuardedTrialPath(
            "/api/v1/policies/evaluate-source", null));
        assertFalse(TrialBypassPredicate.isGuardedTrialPath(
            null, Boolean.TRUE));
    }

    private ContainerRequestContext ctxWithHeaders(String caller, String signature, String timestamp) {
        ContainerRequestContext c = mock(ContainerRequestContext.class);
        if (caller != null) {
            when(c.getHeaderString(TrialBypassPredicate.INTERNAL_CALLER_HEADER)).thenReturn(caller);
        }
        if (signature != null) {
            when(c.getHeaderString(TrialBypassPredicate.INTERNAL_SIGNATURE_HEADER)).thenReturn(signature);
        }
        if (timestamp != null) {
            when(c.getHeaderString(TrialBypassPredicate.INTERNAL_TIMESTAMP_HEADER)).thenReturn(timestamp);
        }
        return c;
    }

    @Test
    @DisplayName("内部调用头齐全（cloud-bff + signature + timestamp）→ true")
    void internalCallerComplete() {
        assertTrue(TrialBypassPredicate.hasInternalCallerCredentials(
            ctxWithHeaders("cloud-bff", "sig", "1700000000")));
    }

    @Test
    @DisplayName("缺签名头 → false")
    void internalCallerMissingSignature() {
        assertFalse(TrialBypassPredicate.hasInternalCallerCredentials(
            ctxWithHeaders("cloud-bff", null, "1700000000")));
    }

    @Test
    @DisplayName("缺时间戳头 → false")
    void internalCallerMissingTimestamp() {
        assertFalse(TrialBypassPredicate.hasInternalCallerCredentials(
            ctxWithHeaders("cloud-bff", "sig", null)));
    }

    @Test
    @DisplayName("caller 值不是 cloud-bff → false")
    void internalCallerWrongValue() {
        assertFalse(TrialBypassPredicate.hasInternalCallerCredentials(
            ctxWithHeaders("attacker", "sig", "1700000000")));
    }

    @Test
    @DisplayName("无任何内部调用头 / null ctx → false")
    void internalCallerAbsent() {
        assertFalse(TrialBypassPredicate.hasInternalCallerCredentials(
            ctxWithHeaders(null, null, null)));
        assertFalse(TrialBypassPredicate.hasInternalCallerCredentials(null));
    }
}
