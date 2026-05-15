package io.aster.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R25-Critical-1: per-segment matrix-param 归一化测试。
 *
 * <p>关键防回归：filter 必须看到与 RESTEasy 路由层一致的归一化 path，否则
 * security 分类与实际路由解耦会产生 auth bypass。
 */
class PathNormalizerTest {

    @Test
    void leadingSlashAdded() {
        assertEquals("/foo", PathNormalizer.normalize("foo"));
        assertEquals("/foo/bar", PathNormalizer.normalize("foo/bar"));
    }

    @Test
    void noMatrixParamsLeftAsIs() {
        assertEquals("/api/v1/policies/foo/rollback",
            PathNormalizer.normalize("/api/v1/policies/foo/rollback"));
    }

    @Test
    void terminalMatrixParamStripped() {
        // R23-original case: matrix params on the LAST segment
        assertEquals("/api/v1/policies/foo/rollback",
            PathNormalizer.normalize("/api/v1/policies/foo/rollback;x"));
        assertEquals("/api/v1/policies/cache",
            PathNormalizer.normalize("/api/v1/policies/cache;jsessionid=abc"));
    }

    @Test
    void intermediateMatrixParamStripped() {
        // R25-Critical-1: 这是 R23 漏掉的情况
        assertEquals("/api/v1/policies/foo/rollback",
            PathNormalizer.normalize("/api/v1/policies/foo;x/rollback"));
        assertEquals("/api/v1/policies/cache",
            PathNormalizer.normalize("/api/v1/policies;v=1/cache"));
        assertEquals("/api/v1/policies/foo/versions",
            PathNormalizer.normalize("/api/v1/policies/foo;jsessionid=xyz/versions"));
    }

    @Test
    void matrixParamOnEverySegmentStripped() {
        // 极端情况：每段都有 matrix params
        assertEquals("/foo/bar/baz",
            PathNormalizer.normalize("/foo;a=1/bar;b=2/baz;c=3"));
    }

    @Test
    void trailingSlashStripped() {
        assertEquals("/api/v1/policies/foo/rollback",
            PathNormalizer.normalize("/api/v1/policies/foo/rollback/"));
    }

    @Test
    void matrixParamAndTrailingSlashCombined() {
        assertEquals("/api/v1/policies/foo/rollback",
            PathNormalizer.normalize("/api/v1/policies/foo;x/rollback/"));
    }

    @Test
    void rootPathPreserved() {
        assertEquals("/", PathNormalizer.normalize("/"));
        assertEquals("/", PathNormalizer.normalize(""));
    }

    @Test
    void nullDefaultsToRoot() {
        assertEquals("/", PathNormalizer.normalize(null));
    }

    @Test
    void emptyMatrixValueOk() {
        // ; followed by nothing (or just '/')
        assertEquals("/foo/bar", PathNormalizer.normalize("/foo;/bar"));
        assertEquals("/foo", PathNormalizer.normalize("/foo;"));
    }

    @Test
    void unrelatedSemicolonInQueryStringNotInScope() {
        // ctx.getUriInfo().getPath() doesn't include query string, but defensive:
        // semicolons inside a hypothetical segment value still get stripped
        assertEquals("/foo/bar",
            PathNormalizer.normalize("/foo;not-a-path-char-but-stripped-anyway/bar"));
    }
}
