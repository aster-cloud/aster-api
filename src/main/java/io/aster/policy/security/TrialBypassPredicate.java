package io.aster.policy.security;

import io.aster.security.PathNormalizer;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * R30: 集中放置所有 "请求是否已被 TrialEndpointGuard 放行" 的判定。
 *
 * <p>背景：R28→R29++ 期间 5 个 filter / handler 各自内联了一份相同的双重
 * 校验逻辑（{@link TrialEndpointGuard#TRIAL_GUARD_PASSED_PROP} 凭证 +
 * normalized path 等于 {@link TrialEndpointGuard#TRIAL_PATH}）。Codex
 * 复审指出长期维护风险 —— 任何一处单独漂移就会破坏整条链的对齐。
 *
 * <p>本类只暴露两个静态方法，无状态、不可继承：
 * <ul>
 *   <li>{@link #isGuardedTrialRequest(ContainerRequestContext)} —— JAX-RS
 *       端用，读取 request property + 归一化路径。</li>
 *   <li>{@link #isGuardedTrialPath(String, Boolean)} —— 非 JAX-RS 上下文
 *       （例如 resource 层 raw endpoint path + 已经取出的 property）。</li>
 * </ul>
 *
 * <p>本类<b>不</b>构造新的 sentinel、<b>不</b>修改 ctx，仅返回布尔值。
 * 调用方负责后续动作（return null、setCurrentTenant("trial") 等），
 * 以保留每个 filter 的清晰职责边界。
 */
public final class TrialBypassPredicate {

    private TrialBypassPredicate() {}

    /**
     * 判断当前 JAX-RS 请求是否处于"已通过 TrialEndpointGuard 三重校验
     * 且命中 trial 专属路径"的状态。
     *
     * <p>归一化路径以匹配 {@link PathNormalizer}，避免 matrix-param 等
     * 视差攻击。
     */
    public static boolean isGuardedTrialRequest(ContainerRequestContext ctx) {
        if (ctx == null) return false;
        Object prop = ctx.getProperty(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP);
        if (!Boolean.TRUE.equals(prop)) return false;
        String rawPath = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getPath();
        return isTrialPath(rawPath);
    }

    /**
     * 非 JAX-RS 上下文使用（例如 resource 层已经拿到原始 path 和已读取的
     * property 值），便于单元测试和不依赖 ctx 的调用点。
     */
    public static boolean isGuardedTrialPath(String rawPath, Boolean guardPassedProp) {
        if (!Boolean.TRUE.equals(guardPassedProp)) return false;
        return isTrialPath(rawPath);
    }

    private static boolean isTrialPath(String rawPath) {
        if (rawPath == null) return false;
        return TrialEndpointGuard.TRIAL_PATH.equals(PathNormalizer.normalize(rawPath));
    }
}
