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

    /** 内部调用方标识头：cloud-bff BFF 经 HMAC 签名访问 evaluate-source 时携带。 */
    public static final String INTERNAL_CALLER_HEADER = "X-Internal-Caller";
    public static final String INTERNAL_SIGNATURE_HEADER = "X-Internal-Signature";
    public static final String INTERNAL_TIMESTAMP_HEADER = "X-Aster-Timestamp";
    private static final String INTERNAL_CALLER_VALUE = "cloud-bff";

    /**
     * 判断请求是否声称自己是 cloud-bff 的<b>内部调用</b>（携带 X-Internal-Caller=cloud-bff
     * 且带签名头 + 时间戳头——即完整的内部调用凭据形态）。
     *
     * <p><b>这只是"声称"，不是认证</b>：签名的真伪由 {@code InternalCallerFilter} 校验。
     * 用途是让 {@link TrialEndpointGuard} 对内部调用<b>跳过</b> Origin/body/IP 这些
     * <b>仅针对匿名浏览器 trial 流量</b>的成本控制闸门——内部调用是服务器到服务器的
     * fetch，没有浏览器 Origin 头，本不该被 trial 的 Origin 白名单拦下。
     *
     * <p><b>安全边界澄清</b>：跳过只让出 trial 的<b>成本控制</b>（per-IP 限流、32KiB body
     * cap、Turnstile），<b>不</b>让出任何业务执行/鉴权能力。伪造 X-Internal-Caller 的请求
     * 会被 {@code InternalCallerFilter} 在 <b>request filter 阶段</b>（读 entity 之前）以
     * invalid_signature 403 拒掉，不会进入 {@code evaluateSource} 资源方法。被让出的 body
     * cap 由全局 {@code quarkus.http.limits.max-body-size}（1M）+ source 字段 {@code @Size}
     * （64KiB）兜底，但实际第一道防线是 HMAC filter 早拒。
     */
    public static boolean hasInternalCallerCredentials(ContainerRequestContext ctx) {
        if (ctx == null) return false;
        return INTERNAL_CALLER_VALUE.equals(ctx.getHeaderString(INTERNAL_CALLER_HEADER))
            && ctx.getHeaderString(INTERNAL_SIGNATURE_HEADER) != null
            && ctx.getHeaderString(INTERNAL_TIMESTAMP_HEADER) != null;
    }
}
