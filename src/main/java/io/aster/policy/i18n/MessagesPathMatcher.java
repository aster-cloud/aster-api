package io.aster.policy.i18n;

/**
 * 界面文案公开读端点的 perimeter 豁免路径匹配（ADR 0018 P2 + ADR 0020 优化 1）。
 *
 * <p>覆盖两个公开只读端点：
 * <ul>
 *   <li>{@code GET /api/v1/messages/{locale}} —— 单 locale 文案树（ADR 0018）</li>
 *   <li>{@code GET /api/v1/messages-manifest} —— 各 locale 版本清单（ADR 0020）</li>
 * </ul>
 *
 * <p>抽成纯静态工具，给 {@code TenantFilter} 和 {@code RequestSignatureFilter} 共用同一份
 * 安全敏感的匹配逻辑——避免两个 filter 各写一份导致行为漂移。
 *
 * <p>**精确匹配**（Codex 安全审查 86/100 的收紧建议）：messages 端点只暴露
 * {@code /{locale}} 单段，匹配只接受恰好这一段、段内无斜杠、且不是 {@code .}/{@code ..}
 * （防 {@code /api/v1/messages/../policies/evaluate} 之类路径穿越绕过 perimeter，或
 * {@code /api/v1/messages/en-US/extra} 多段子路径被整子树豁免）；manifest 端点是精确
 * 路径（无子路径）。豁免范围严格收敛到实际端点，不豁免整个子树。
 *
 * <p>授权语义仍由资源内部的 locale 可用性开关守（未启用 locale → 404）；此匹配只决定
 * "是否在 perimeter 上放行匿名读请求"，不替代可用性校验。
 */
public final class MessagesPathMatcher {

    private static final String PREFIX_SLASH = "/api/v1/messages/";
    private static final String PREFIX_NOSLASH = "api/v1/messages/";

    /** ADR 0020：messages 版本清单端点（精确路径，无子路径）。 */
    private static final String MANIFEST_SLASH = "/api/v1/messages-manifest";
    private static final String MANIFEST_NOSLASH = "api/v1/messages-manifest";

    private MessagesPathMatcher() {}

    /**
     * 是否为界面文案公开读路径（单 locale 文案 或 版本清单）。两个 filter 用此统一判定。
     */
    public static boolean isPublicMessagesReadPath(String path) {
        return isSingleLocaleMessagesPath(path) || isManifestPath(path);
    }

    /**
     * 判断 path 是否恰好是 {@code /api/v1/messages/<locale>}（单段 locale，无尾随子路径，
     * 不含路径穿越段）。带/不带前导斜杠两种形态都接受（与既有 lexicon 匹配一致）。
     */
    public static boolean isSingleLocaleMessagesPath(String path) {
        if (path == null) {
            return false;
        }
        String locale;
        if (path.startsWith(PREFIX_SLASH)) {
            locale = path.substring(PREFIX_SLASH.length());
        } else if (path.startsWith(PREFIX_NOSLASH)) {
            locale = path.substring(PREFIX_NOSLASH.length());
        } else {
            return false;
        }
        return isSingleSafeSegment(locale);
    }

    /** 判断 path 是否为 {@code /api/v1/messages-manifest}（精确，无子路径）。 */
    public static boolean isManifestPath(String path) {
        return MANIFEST_SLASH.equals(path) || MANIFEST_NOSLASH.equals(path);
    }

    /**
     * locale 段必须非空、不含斜杠（单段）、不是路径穿越占位（{@code .}/{@code ..}）。
     * 不对 locale 做格式白名单——未知/畸形 locale 交给 MessagesResource 的可用性开关
     * 返回 404，perimeter 只负责不把多段/穿越路径当作 messages 端点放行。
     */
    private static boolean isSingleSafeSegment(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        if (segment.indexOf('/') >= 0) {
            return false; // 多段子路径（如 en-US/extra），不是合法 messages 端点
        }
        return !".".equals(segment) && !"..".equals(segment);
    }
}
