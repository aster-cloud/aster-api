package io.aster.security;

/**
 * R25-Critical-1: 路径归一化工具，专门处理 filter 与 RESTEasy 路由层的"matrix-param 视差"。
 *
 * <p>RFC 3986 允许在每个 path segment 后面加 {@code ;key=val} matrix params。RESTEasy
 * 在路由匹配前会**逐段**剥离这些 matrix params，所以 {@code /api/v1/policies/foo;x/rollback}
 * 会被路由匹配为 {@code /api/v1/policies/foo/rollback} 并命中 {@code @Path("/{policyId}/rollback")}。
 *
 * <p>但是我们的 servlet-level filter 拿到的 path 是**原始**的（含 matrix params）。如果
 * 用 {@code path.split(";")[0]} 这种"在第一个分号处截断"的方式做归一化，会把后面的所有段也
 * 丢掉 —— 例如 {@code /foo;x/rollback} 被截成 {@code /foo}，{@code endsWith("/rollback")} 失败，
 * 端点变成无鉴权可达。
 *
 * <p>本工具按 {@code /} 分段，每段独立 strip {@code ;...}，然后再拼回去 —— 这与 RESTEasy
 * 路由层的归一化对齐。
 *
 * <p>例：
 * <pre>
 *   /api/v1/policies/foo;x/rollback   → /api/v1/policies/foo/rollback
 *   /api/v1/policies;v=1/cache        → /api/v1/policies/cache
 *   /foo/rollback;jsessionid=abc      → /foo/rollback
 *   /foo;a=1/bar;b=2/baz              → /foo/bar/baz
 * </pre>
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    /**
     * Normalize a request path for security classification.
     *
     * <p>Strips per-segment matrix parameters ({@code ;key=val}) and trims a trailing
     * slash. Ensures the result starts with {@code /}.
     *
     * @param rawPath path from {@link jakarta.ws.rs.core.UriInfo#getPath()} (may or
     *                may not have leading slash)
     * @return normalized path always starting with {@code /}; never null
     */
    public static String normalize(String rawPath) {
        if (rawPath == null) return "/";
        String p = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        if (p.indexOf(';') >= 0) {
            // Per-segment strip
            String[] segs = p.split("/", -1);
            for (int i = 0; i < segs.length; i++) {
                int semi = segs[i].indexOf(';');
                if (semi >= 0) {
                    segs[i] = segs[i].substring(0, semi);
                }
            }
            p = String.join("/", segs);
        }
        // Trim trailing slash (but keep root "/" as-is)
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
