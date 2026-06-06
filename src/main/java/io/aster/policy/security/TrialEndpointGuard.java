package io.aster.policy.security;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Marketing-tier trial 端点的专属防刷闸门。
 *
 * <p>仅作用于 {@code /api/v1/policies/evaluate-source}，且仅在
 * {@code aster.security.evaluate-source.trial.enabled=true} 时生效。
 *
 * <p>层级（依次校验，任一不过即 short-circuit）：
 * <ol>
 *   <li><b>Origin allowlist 提示性校验</b> —— 必须命中
 *       {@code aster.security.trial.allowed-origins}。
 *       <b>注意</b>：{@code Origin} 头是浏览器自动设置的，非浏览器客户端（curl、
 *       脚本、Postman、攻击工具）可以任意伪造它。因此这只是一道"提示性"闸门，
 *       而 <b>不</b> 是身份验证。它能挡住的是：浏览器里运行的、来源不在允许列表
 *       的脚本（同源/跨域策略的副产物）。对于非浏览器流量，必须依赖后续的
 *       per-IP 限流 + body-size cap + 并发槽位三道闸门。
 *       <p>operator 心智：trial = 公开匿名端点，所有防护都是"成本控制" 而非
 *       "身份认证"；如果未来要避免 cloud 直接被 burn token，请走 cloud-side
 *       proxy + Turnstile / 后端短期 token 这类真鉴权。</li>
 *   <li><b>Body size hard cap</b> —— 默认 32 KiB。强制读取 Content-Length；
 *       Transfer-Encoding、缺失或不可解析的 Content-Length 一律拒绝。
 *       这一条比 upstream {@code quarkus.http.limits.max-body-size} 更早生效，
 *       避免大请求被下游 readAllBytes 吃进堆。</li>
 *   <li><b>Per-IP 滑动窗口</b> —— 默认 10 req / 60s + 60 req / 3600s 两层。
 *       命中即 429，带 Retry-After。</li>
 *   <li><b>Per-IP 并发请求数</b> —— 默认 2。命中即 429。请求完成后必须 release。</li>
 * </ol>
 *
 * <p><b>XFF 信任模型</b>：per-IP 闸门依赖 client-ip 提取。默认 <b>不</b> 信任
 * {@code X-Forwarded-For} / {@code X-Real-IP}，因为客户端可任意伪造这些头来
 * 把限流分桶切到任意 key。仅当部署架构在 ingress 处 <b>重写</b>（非 append）
 * 这些头时，operator 才应显式设
 * {@code aster.security.trial.trust-forwarded-for=true}。
 * 默认值是看 socket 远端地址 —— 在 k3s ingress / Cloudflare 反代部署下，所有
 * 流量都从 ingress IP 出来，限流会变成"全局每 IP（即 ingress）每分钟 10 次"，
 * 这是 fail-closed 的安全默认；operator 看到限流过严时再显式启用 XFF 信任。</li>
 *
 * <p>优先级：{@link Priorities#AUTHENTICATION} - 100，确保在
 * {@code InternalCallerFilter}（AUTHENTICATION + 50）之前生效。
 *
 * <p>设计权衡：
 * <ul>
 *   <li>选择基于 IP 而非租户，因为 trial 流量来自匿名访客，没有租户上下文。</li>
 *   <li>不使用 {@link RateLimitFilter}，因为后者把限流额度和租户绑定；这里要
 *       trial 专属、IP-bucket、更低额度。</li>
 *   <li>滑动窗口存量复用 {@link RateLimiter}（同一 ConcurrentHashMap 后端），
 *       key 前缀 {@code trial:ip:&lt;ip&gt;:1m} / {@code :1h} 区分两层窗口；连接计数器
 *       用 {@code trial:ip:&lt;ip&gt;}。</li>
 * </ul>
 *
 * <p>Response filter 与 Request filter 配对：Response filter 在请求完成时
 * release 连接计数器，否则一旦失败/抖动会泄漏 → 永久占用 slots。
 */
@ApplicationScoped
public class TrialEndpointGuard {

    private static final Logger LOG = Logger.getLogger(TrialEndpointGuard.class);

    /** 仅 evaluate-source 受保护；其他路径全部放行。 */
    public static final String TRIAL_PATH = "/api/v1/policies/evaluate-source";

    /**
     * 标记本请求已经通过 trial guard 的全部闸门。下游 filter
     * （{@link RequestSignatureFilter} 与 {@code InternalCallerFilter}）必须
     * 在请求路径正好等于 {@link #TRIAL_PATH} 的前提下检查此 property，**只有**
     * 该 property 为 {@link Boolean#TRUE} 时才放过 HMAC 签名校验。
     *
     * <p>设计意图：避免 trial 标志单独打开就把 evaluate-source 变成完全无验证
     * 的匿名端点 —— guard 通过才算入场凭证，且这一凭证只对这一个请求生命周期有效，
     * 不会跨请求传递。
     */
    public static final String TRIAL_GUARD_PASSED_PROP =
        "io.aster.policy.security.TrialEndpointGuard.passed";

    /**
     * 标记 ctx 是否消耗了一个并发 slot，供 response filter 判断要不要 release。
     * Property key 私有，避免被业务代码意外清掉。
     */
    private static final String SLOT_HOLDER_PROP =
        "io.aster.policy.security.TrialEndpointGuard.slot";

    @Inject
    RateLimiter rateLimiter;

    /**
     * R31-4：cf Turnstile 验证器。默认 enabled=false 时 verify 直接返回 true，
     * 零开销；启用后在 body-size 通过、per-IP 限流之前调一次。
     */
    @Inject
    TurnstileVerifier turnstileVerifier;

    /**
     * Vert.x RoutingContext —— 用来读取 socket 远端地址（在 trust-forwarded-for
     * 默认关闭时优先于 XFF）。JAX-RS ContainerRequestContext 不暴露源 IP，
     * 走 Vert.x 通道。
     */
    @Inject
    RoutingContext routingContext;

    @ConfigProperty(name = "aster.security.evaluate-source.trial.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * 逗号分隔的 Origin 白名单。空字符串等同于关闭 Origin 校验（不推荐生产开启）。
     * 默认与 marketing 站点对齐。
     */
    @ConfigProperty(
        name = "aster.security.trial.allowed-origins",
        defaultValue = "https://aster-lang.dev,https://www.aster-lang.dev,"
            + "https://aster-lang.cloud,https://www.aster-lang.cloud"
    )
    String allowedOriginsCsv;

    @ConfigProperty(name = "aster.security.trial.max-body-bytes", defaultValue = "32768")
    int maxBodyBytes;

    @ConfigProperty(name = "aster.security.trial.per-ip.minute-max", defaultValue = "10")
    int perIpMinuteMax;

    @ConfigProperty(name = "aster.security.trial.per-ip.hour-max", defaultValue = "60")
    int perIpHourMax;

    @ConfigProperty(name = "aster.security.trial.per-ip.concurrent-max", defaultValue = "2")
    int perIpConcurrentMax;

    /**
     * 是否信任 {@code X-Forwarded-For} / {@code X-Real-IP} 头作为客户端真实 IP。
     *
     * <p><b>默认 false</b>（fail-closed）：客户端可任意伪造 XFF 来污染 per-IP 分桶；
     * 只有当部署架构中存在一个 <b>始终重写</b>（非 append）XFF 的可信反代时，
     * operator 才应显式打开。
     *
     * <p>关闭时，client IP = Vert.x socket 远端地址。注意在 k3s ingress 模式下
     * 该地址会是 ingress 自身的 IP；所有 trial 流量会落到同一个 IP 分桶，
     * 限流变成全局每分钟 10 次。这是合理的 fail-closed 默认 —— operator 看到
     * 限流过严时再启用 trust-forwarded-for，启用前确认 ingress 配置。
     */
    @ConfigProperty(name = "aster.security.trial.trust-forwarded-for", defaultValue = "false")
    boolean trustForwardedFor;

    /**
     * 配套 {@code aster.security.evaluate-source.public}（更粗粒度的全量旁路），
     * 用于启动时互斥检查。两个 flag 同时打开是 operator 心智错误：{@code .public}
     * 完全跳过 InternalCallerFilter 和 TrialEndpointGuard，trial 配置失效。
     */
    @ConfigProperty(name = "aster.security.evaluate-source.public", defaultValue = "false")
    boolean evaluateSourcePublic;

    private volatile Set<String> allowedOrigins = Set.of();

    /**
     * 启动时输出 trial 端点配置的健康检查。三类信号：
     *
     * <ul>
     *   <li><b>WARN</b>：trial.enabled=true 且 evaluate-source.public=true 同时打开
     *       —— 两者语义重叠且 .public 优先生效，trial 配置失效。operator 应只设一个。</li>
     *   <li><b>WARN</b>：trial.enabled=true 且 allowed-origins 为空 —— 任何 Origin
     *       都会被放过。生产 anti-pattern，但代码不阻拦。</li>
     *   <li><b>WARN</b>：trial.enabled=true 且 trust-forwarded-for=true —— 如果反代
     *       不重写 XFF，limit 可被伪造。operator 必须确认 ingress 行为。</li>
     * </ul>
     *
     * <p>R29+ 修订：prod profile 对 .public+.trial 同开 / trial+空 origins
     * 改为 fail-fast。dev/test 保持 warn。trust-forwarded-for=true 仍 warn
     * （它是合法的生产配置，只是要求 ingress 正确处理 XFF）。
     */
    void auditStartupConfig(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.debugf("trial endpoint guard disabled (aster.security.evaluate-source.trial.enabled=false)");
            return;
        }
        LOG.infof("trial endpoint guard ENABLED: max-body=%d, minute-max=%d, hour-max=%d, "
                + "concurrent-max=%d, trust-xff=%s, origins=%s",
            maxBodyBytes, perIpMinuteMax, perIpHourMax, perIpConcurrentMax,
            trustForwardedFor, parseOrigins(allowedOriginsCsv));

        boolean isProd = io.quarkus.runtime.LaunchMode.current()
            == io.quarkus.runtime.LaunchMode.NORMAL;

        if (evaluateSourcePublic) {
            // R29 Codex audit：生产 profile 下 .public 与 .trial 同开是配置冲突
            // ——.public 完全绕过本守门，trial 限流形同虚设。提升为 fail-fast
            // 避免 operator 误开后 marketing 流量裸奔。
            String msg = "Both aster.security.evaluate-source.public=true AND "
                + "aster.security.evaluate-source.trial.enabled=true are set. "
                + ".public is no-defense bypass and would mask the trial limiter. "
                + "Pick exactly one for production.";
            if (isProd) {
                throw new IllegalStateException("aster-api startup aborted: " + msg);
            }
            LOG.warnf("CONFIG SMELL (dev/test profile): %s", msg);
        }
        if (parseOrigins(allowedOriginsCsv).isEmpty()) {
            // 同样是生产风险：开 trial 但没有 Origin 白名单 = 任何来源都能
            // 进入限流前的第一道闸门，等于把成本控制减半。
            String msg = "aster.security.evaluate-source.trial.enabled=true but "
                + "aster.security.trial.allowed-origins is empty — Origin allowlist OFF. "
                + "Any caller (including curl with forged Origin) reaches the per-IP limiter.";
            if (isProd) {
                throw new IllegalStateException("aster-api startup aborted: " + msg);
            }
            LOG.warnf("CONFIG SMELL (dev/test profile): %s", msg);
        }
        if (trustForwardedFor) {
            // 这一项保持 WARN：trustForwardedFor=true 在生产是合法配置，只是
            // 要求 ingress 正确处理 XFF。fail-fast 会误伤合法部署。
            LOG.warnf("CONFIG SMELL: aster.security.trial.trust-forwarded-for=true. The ingress in "
                + "front of aster-api MUST be configured to OVERWRITE X-Forwarded-For (not append). "
                + "Otherwise clients can forge XFF to shard around per-IP rate limits.");
        }
    }

    void init() {
        // ConfigProperty 字段在 CDI 初始化后才有值；这里在每次过滤时按需重建一次
        // Set。CSV 一般 4-6 条，开销可忽略；同时支持运行时通过 K8s ConfigMap
        // patch + restart 调整。
        this.allowedOrigins = parseOrigins(allowedOriginsCsv);
    }

    /** Package-private helper for testability + startup audit. */
    static Set<String> parseOrigins(String csv) {
        if (csv == null) return Set.of();
        Set<String> built = new HashSet<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) built.add(trimmed);
        }
        return Set.copyOf(built);
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 100)
    public Response filterRequest(ContainerRequestContext ctx) {
        if (!enabled) return null;

        String p = io.aster.security.PathNormalizer.normalize(ctx.getUriInfo().getPath());
        if (!TRIAL_PATH.equals(p)) return null;

        // OPTIONS preflight 由 CorsFilter 处理，不在 trial guard 管辖。
        if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) return null;

        init(); // 刷新 allowedOrigins set

        // 1) Origin 白名单
        String origin = ctx.getHeaderString("Origin");
        if (!allowedOrigins.isEmpty()) {
            if (origin == null || !allowedOrigins.contains(origin)) {
                LOG.warnf("trial guard rejected: origin=%s (allowed=%s)", origin, allowedOrigins);
                return forbidden("origin_not_allowed",
                    "Trial endpoint requires Origin in the marketing-site allowlist.");
            }
        }

        // 2) Body size — 强严格姿态
        //
        // trial 是公开匿名端点，必须靠 Content-Length 头预先封顶，不能依赖下游
        // readAllBytes() 兜底。三类请求一律拒绝：
        //
        //   (a) Transfer-Encoding 任意取值（含 chunked / identity / gzip）
        //       —— 一旦上游分块传输，Content-Length 失效，闸门绕过；
        //   (b) Content-Length 缺失或为空 —— 411 Length Required；
        //   (c) Content-Length 不可解析或为负数 / 大于 maxBodyBytes —— 413。
        //
        // 这一姿态比"chunked 上传也是合法情况"的旧版宽松策略严格得多，但 trial
        // 流量都是浏览器 fetch JSON POST，每个请求都自带合法 CL，损失为零。
        String te = ctx.getHeaderString("Transfer-Encoding");
        if (te != null && !te.isBlank()) {
            LOG.warnf("trial guard rejected: Transfer-Encoding=%s not allowed", te);
            return Response.status(413)
                .entity(Map.of(
                    "error", "transfer_encoding_not_allowed",
                    "message", "Trial endpoint requires fixed Content-Length; "
                        + "Transfer-Encoding (including chunked) is rejected to enforce the "
                        + maxBodyBytes + "-byte body cap."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
        String cl = ctx.getHeaderString("Content-Length");
        if (cl == null || cl.isBlank()) {
            LOG.warnf("trial guard rejected: Content-Length header required");
            return Response.status(411)
                .entity(Map.of(
                    "error", "content_length_required",
                    "message", "Trial endpoint requires a Content-Length header."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
        long bytes;
        try {
            bytes = Long.parseLong(cl.trim());
        } catch (NumberFormatException nfe) {
            LOG.warnf("trial guard rejected: invalid Content-Length=%s", cl);
            return Response.status(411)
                .entity(Map.of(
                    "error", "invalid_content_length",
                    "message", "Content-Length must be a non-negative integer."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
        if (bytes < 0 || bytes > maxBodyBytes) {
            LOG.warnf("trial guard rejected: body=%d bytes > limit=%d", bytes, maxBodyBytes);
            return Response.status(413)
                .entity(Map.of(
                    "error", "payload_too_large",
                    "limit", maxBodyBytes,
                    "received", bytes,
                    "message", "Trial endpoint caps request body at "
                        + maxBodyBytes + " bytes."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        // R31-4：Turnstile 校验（per ADR-0012）。enabled=false 时直接 true，
        // 零开销；enabled=true 需要 X-Trial-Turnstile-Token 头。
        // 放在 Origin / body-size 之后、per-IP 限流之前：让显式合法的
        // browser playground 提交不消耗 IP 配额，反而让 token 缺失或
        // 伪造的请求在还没"占 IP 槽位"前就被拒。
        String ip = clientIp(ctx, routingContext, trustForwardedFor);
        String turnstileToken = ctx.getHeaderString("X-Trial-Turnstile-Token");
        // turnstileVerifier 在 unit-test 反射构造的 guard 上可能为 null —
        // 等价于 enabled=false 语义直接放行，CDI 生产环境永不为 null。
        if (turnstileVerifier != null && !turnstileVerifier.verify(turnstileToken, ip)) {
            LOG.warnf("trial guard rejected: ip=%s turnstile failed", ip);
            return Response.status(403)
                .entity(Map.of(
                    "error", "turnstile_failed",
                    "message", "Trial endpoint requires a valid Turnstile token. "
                        + "Refresh the playground widget and retry."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        // 3) Per-IP 滑动窗口（双层）

        String minuteKey = "trial:ip:" + ip + ":1m";
        Duration minute = Duration.ofSeconds(60);
        if (!rateLimiter.tryAcquire(minuteKey, perIpMinuteMax, minute)) {
            long resetAt = rateLimiter.resetAtEpochSecond(minuteKey, minute);
            long retry = Math.max(1, resetAt - java.time.Instant.now().getEpochSecond());
            LOG.warnf("trial guard rejected: ip=%s minute-rate-exceeded", ip);
            return rateLimited("rate_limit_minute", retry, perIpMinuteMax, resetAt);
        }

        String hourKey = "trial:ip:" + ip + ":1h";
        Duration hour = Duration.ofSeconds(3600);
        if (!rateLimiter.tryAcquire(hourKey, perIpHourMax, hour)) {
            long resetAt = rateLimiter.resetAtEpochSecond(hourKey, hour);
            long retry = Math.max(1, resetAt - java.time.Instant.now().getEpochSecond());
            LOG.warnf("trial guard rejected: ip=%s hour-rate-exceeded", ip);
            return rateLimited("rate_limit_hour", retry, perIpHourMax, resetAt);
        }

        // 4) Per-IP 并发槽位
        String slotKey = "trial:ip:" + ip;
        if (!rateLimiter.tryAcquireConnection(slotKey, perIpConcurrentMax)) {
            LOG.warnf("trial guard rejected: ip=%s concurrent-limit=%d", ip, perIpConcurrentMax);
            return Response.status(429)
                .header("Retry-After", "1")
                .entity(Map.of(
                    "error", "too_many_concurrent",
                    "limit", perIpConcurrentMax,
                    "message", "Trial endpoint allows at most "
                        + perIpConcurrentMax + " concurrent request(s) per IP."
                ))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        // 通过所有闸门：颁发 trial 入场凭证 + 记录 slot key 供 response filter release
        ctx.setProperty(TRIAL_GUARD_PASSED_PROP, Boolean.TRUE);
        ctx.setProperty(SLOT_HOLDER_PROP, slotKey);
        return null;
    }

    /**
     * Response 端 hook —— 必须释放 request 端占用的并发 slot。
     *
     * <p>选择 response filter（而非 try/finally 包裹 endpoint），是因为 endpoint
     * 用 Uni 异步执行；如果 endpoint 在 reactive 链中抛错，hook 仍然能被
     * RESTEasy 调用，避免泄漏。
     */
    @org.jboss.resteasy.reactive.server.ServerResponseFilter(
        priority = Priorities.AUTHENTICATION - 100)
    public void filterResponse(ContainerRequestContext ctx) {
        Object holder = ctx.getProperty(SLOT_HOLDER_PROP);
        if (holder instanceof String key) {
            rateLimiter.releaseConnection(key);
        }
    }

    private static Response forbidden(String reason, String message) {
        return Response.status(403)
            .entity(Map.of("error", reason, "message", message))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private static Response rateLimited(String reason, long retryAfterSec,
                                        int limit, long resetAtEpochSec) {
        return Response.status(429)
            .header("Retry-After", String.valueOf(retryAfterSec))
            .header("X-RateLimit-Limit", String.valueOf(limit))
            .header("X-RateLimit-Remaining", "0")
            .header("X-RateLimit-Reset", String.valueOf(resetAtEpochSec))
            .entity(Map.of(
                "error", "too_many_requests",
                "reason", reason,
                "limit", limit,
                "retryAfter", retryAfterSec
            ))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    /**
     * 提取请求 IP，per-IP 限流分桶的 key 来源。
     *
     * <p>策略由 {@code trustForwardedFor} 决定：
     * <ul>
     *   <li><b>false</b>（默认，fail-closed）：直接读 Vert.x socket 远端地址。
     *       XFF/X-Real-IP 头被忽略，因为它们可任意伪造。</li>
     *   <li><b>true</b>（operator 显式开启）：优先 XFF 第一段，其次 X-Real-IP，
     *       最后再 fallback socket 地址。仅在反代会重写而非 append XFF 时使用。</li>
     * </ul>
     *
     * <p>包级可见以方便单测。
     */
    static String clientIp(ContainerRequestContext ctx,
                           RoutingContext routing,
                           boolean trustForwardedFor) {
        if (trustForwardedFor) {
            // 最高优先级：Cloudflare 边缘写入的不可伪造单值头。本服务经 Cloudflare
            // Tunnel 暴露，而 Cloudflare 对客户端自带的 X-Forwarded-For 是 append
            // 而非覆盖（XFF 首段可被攻击者伪造），CF-Connecting-IP 则由边缘写入、
            // 单值、不可伪造（Cloudflare 官方推荐用它还原访客 IP）。
            String cf = ctx.getHeaderString("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) return cf.trim();
            // 次选：XFF 首段（仅当反代覆盖而非 append 时才完全可信）。
            String xff = ctx.getHeaderString("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",", 2)[0].trim();
                if (!first.isEmpty()) return first;
            }
            String real = ctx.getHeaderString("X-Real-IP");
            if (real != null && !real.isBlank()) return real.trim();
        }
        // 默认：socket 源地址（防伪造）。routing 在 filter 执行栈里总是
        // 有的；只有在测试场景下可能为 null，此时回退 "unknown"。
        if (routing != null && routing.request() != null
                && routing.request().remoteAddress() != null) {
            String host = routing.request().remoteAddress().host();
            if (host != null && !host.isBlank()) return host;
        }
        return "unknown";
    }

    /** Package-private for unit-test use. */
    static List<String> sampleAllowedOriginsForDocs() {
        return List.of(
            "https://aster-lang.dev",
            "https://www.aster-lang.dev",
            "https://aster-lang.cloud",
            "https://www.aster-lang.cloud"
        );
    }
}
