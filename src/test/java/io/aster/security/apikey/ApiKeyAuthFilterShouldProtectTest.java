package io.aster.security.apikey;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R21-Critical-1：ApiKeyAuthFilter.shouldProtect 分类回归测试。
 *
 * <p>背景：生产 k3s 把 {@code aster.security.signature.enabled=false}，让全局
 * RequestSignatureFilter 失效以适应浏览器直连场景。突变端点（rollback / cache /
 * versions）失去全局兜底，必须由本 filter 显式覆盖。
 *
 * <p>不需要 Quarkus 上下文 —— 反射拿 private static method，直接断言路径分类。
 */
class ApiKeyAuthFilterShouldProtectTest {

    private static boolean call(String path) throws Exception {
        Method m = ApiKeyAuthFilter.class.getDeclaredMethod("shouldProtect", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, path);
    }

    @Test
    void evaluateEndpointsRequireApiKey() throws Exception {
        assertTrue(call("/api/v1/policies/evaluate"));
        assertTrue(call("/api/v1/policies/evaluate-json"));
        assertTrue(call("/api/v1/policies/evaluate/batch"));
        // 无前导斜杠也要保护
        assertTrue(call("api/v1/policies/evaluate"));
    }

    @Test
    void evaluateSourceIsExcluded() throws Exception {
        // evaluate-source 由 InternalCallerFilter 守护，不走 API key
        assertFalse(call("/api/v1/policies/evaluate-source"));
    }

    @Test
    void rollbackRequiresApiKey() throws Exception {
        // R21-Critical-1: 突变端点必须鉴权
        assertTrue(call("/api/v1/policies/aster.finance.loan.eval/rollback"));
        assertTrue(call("/api/v1/policies/some-policy-id/rollback"));
    }

    @Test
    void versionListRequiresApiKey() throws Exception {
        // R21-Critical-1: 历史查询必须鉴权
        assertTrue(call("/api/v1/policies/aster.finance.loan.eval/versions"));
    }

    @Test
    void cacheClearRequiresApiKey() throws Exception {
        // R21-Critical-1: 缓存清理是突变操作
        assertTrue(call("/api/v1/policies/cache"));
    }

    @Test
    void anonymousReadEndpointsAreNotProtected() throws Exception {
        // 元数据查询无副作用，匿名可读
        assertFalse(call("/api/v1/policies/schema"));
        assertFalse(call("/api/v1/policies/validate"));
    }

    @Test
    void unrelatedPathsAreNotProtected() throws Exception {
        assertFalse(call("/api/v1/lexicons"));
        assertFalse(call("/api/v1/admin/lexicons"));
        assertFalse(call("/q/health/live"));
        assertFalse(call("/api/internal/snapshot/full"));
        assertFalse(call("/"));
    }

    @Test
    void endpointsThatLookSimilarButAreNotPoliciesAreUnprotected() throws Exception {
        // 防御 endsWith("/rollback") 误伤兄弟路径
        assertFalse(call("/api/v1/admin/lexicons/rollback"));
        // versions 同样防误伤
        assertFalse(call("/api/v1/audit/versions"));
    }

    // ============================================================
    // R23-Critical-1: matrix-parameter bypass regression guards
    // ============================================================

    @Test
    void matrixParamsDoNotBypassEvaluate() throws Exception {
        // JAX-RS @Path("/evaluate") matches /evaluate;x，filter 必须 protect
        assertTrue(call("/api/v1/policies/evaluate;x"));
        assertTrue(call("/api/v1/policies/evaluate;jsessionid=abc"));
        assertTrue(call("/api/v1/policies/evaluate-json;sid=1"));
        assertTrue(call("/api/v1/policies/evaluate/batch;x"));
    }

    @Test
    void matrixParamsDoNotBypassRollback() throws Exception {
        assertTrue(call("/api/v1/policies/foo/rollback;x"));
        assertTrue(call("/api/v1/policies/aster.finance.loan/rollback;jsessionid=abc"));
    }

    @Test
    void matrixParamsDoNotBypassVersions() throws Exception {
        assertTrue(call("/api/v1/policies/foo/versions;x"));
    }

    @Test
    void matrixParamsDoNotBypassCache() throws Exception {
        assertTrue(call("/api/v1/policies/cache;x"));
        assertTrue(call("/api/v1/policies/cache;sid=1"));
    }

    @Test
    void trailingSlashDoesNotBypass() throws Exception {
        // 防尾斜杠（/rollback/）和 matrix+slash 组合
        assertTrue(call("/api/v1/policies/foo/rollback/"));
        assertTrue(call("/api/v1/policies/foo/versions/"));
        assertTrue(call("/api/v1/policies/cache/"));
    }

    @Test
    void matrixParamsOnUnrelatedPathsStillUnprotected() throws Exception {
        // 防误伤：matrix-param 不应让无关 path 突然被保护
        assertFalse(call("/api/v1/lexicons;x"));
        assertFalse(call("/q/health;x"));
    }

    // ============================================================
    // R25-Critical-1: per-segment matrix-param normalization regression
    // ============================================================

    @Test
    void intermediateSegmentMatrixParamDoesNotBypassRollback() throws Exception {
        // RESTEasy 按段剥离：foo;x → foo，路由仍命中 @Path("/{policyId}/rollback")。
        // R23 的"在第一个 ; 处截断"逻辑会把 /foo;x/rollback 砍成 /foo，丢失 /rollback。
        // R25 改成 per-segment 归一化后，必须能识别。
        assertTrue(call("/api/v1/policies/foo;x/rollback"));
        assertTrue(call("/api/v1/policies/foo;jsessionid=abc/rollback"));
        assertTrue(call("/api/v1/policies/aster.finance.loan;v=1/rollback"));
    }

    @Test
    void intermediateSegmentMatrixParamDoesNotBypassVersions() throws Exception {
        assertTrue(call("/api/v1/policies/foo;x/versions"));
    }

    @Test
    void matrixParamOnPoliciesSegmentDoesNotBypassCache() throws Exception {
        // 极端：matrix params 落在 /policies 段
        assertTrue(call("/api/v1/policies;v=1/cache"));
    }

    @Test
    void multiSegmentMatrixParamDoesNotBypass() throws Exception {
        // 每一段都加 matrix params
        assertTrue(call("/api/v1/policies;a=1/foo;b=2/rollback"));
        assertTrue(call("/api/v1/policies;a=1/foo;b=2/rollback;c=3"));
    }
}
