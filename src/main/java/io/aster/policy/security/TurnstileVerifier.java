package io.aster.policy.security;

import io.aster.common.JacksonMappers;
import io.aster.common.http.SharedWebClient;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * R31-4：Cloudflare Turnstile token 校验（per ADR-0012）。
 *
 * <p>调用模型：trial 请求带 {@code X-Trial-Turnstile-Token} header；
 * {@link TrialEndpointGuard} 在 Origin / Content-Length 通过后但 per-IP 限流
 * 前调 {@link #verify(String, String)}：
 * <ul>
 *   <li>{@link #enabled} = false → 直接返回 true（dev / podman / local）</li>
 *   <li>{@link #enabled} = true 且 token 为空 → 返回 false（fail-closed）</li>
 *   <li>token 非空 → 调用 cf siteverify endpoint，cache 60s</li>
 *   <li>cf 不可达（超时 / 5xx）→ 返回 false（fail-closed，发 503）</li>
 * </ul>
 *
 * <p>默认 enabled=false，零开销；secret 缺失时 {@code @ConfigProperty} 用
 * Optional 包装避免启动失败。生产 profile 启用时 fail-fast 检查 secret 必填。
 *
 * <p>缓存设计：用 token SHA-256 hash 作 key（不存原 token），TTL 60s。
 * 这样同一用户在一次 trial 会话里多次提交不需要每次都打 cf。
 */
@ApplicationScoped
public class TurnstileVerifier {

    private static final Logger LOG = Logger.getLogger(TurnstileVerifier.class);
    private static final String CF_VERIFY_URL =
        "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX = 10_000;

    @ConfigProperty(name = "aster.security.trial.turnstile.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * cf 的 secret key。{@code Optional} 让 ConfigMapping 不强制启动期存在；
     * enabled=true 但缺 secret 时本类 fail-closed 拒绝所有 verify。
     */
    @ConfigProperty(name = "aster.security.trial.turnstile.secret")
    Optional<String> secret;

    @ConfigProperty(name = "aster.security.trial.turnstile.timeout-ms", defaultValue = "3000")
    int timeoutMs;

    @Inject
    SharedWebClient sharedWebClient;

    /**
     * 缓存：token-sha256 → (timestamp, verified)。
     * 简易 LRU：达上限时清最旧一半。
     */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long timestampMs, boolean verified) {
        boolean isFresh(long nowMs) {
            return nowMs - timestampMs < CACHE_TTL.toMillis();
        }
    }

    /**
     * 校验一个 trial 请求的 Turnstile token。
     *
     * <p><b>线程契约：</b>方法签名是同步的，但内部用 {@code future.get(timeout)}
     * 阻塞等待 cf siteverify 响应。所以：
     * <ul>
     *   <li><b>禁止</b>在 Vert.x event-loop 线程上调用 —— 会饿死 reactor。</li>
     *   <li>调用方必须在 worker pool / virtual thread 上跑（Quarkus 默认
     *       @ServerRequestFilter 跑 event loop；加 @Blocking 或 @RunOnVirtualThread）。</li>
     *   <li>R32 audit P0：TrialEndpointGuard.filterRequest 在 enabled=false 时
     *       根本不调本方法；enabled=true 时该方法在 cf 慢响应下会卡住 reactor。
     *       解决：调用方按需 offload，或用 {@link #verifyAsync(String, String)}。</li>
     * </ul>
     *
     * @param token cf widget 返回的 token，可能为 null / 空
     * @param remoteIp 客户端 IP，用于 cf 端 fraud-detection
     * @return true = 通过；false = 拒绝（未启用时直接返回 true 放行，零开销）
     */
    public boolean verify(String token, String remoteIp) {
        if (!enabled) return true;

        if (token == null || token.isBlank()) {
            LOG.debug("Turnstile enabled but token missing → reject");
            return false;
        }
        if (secret.isEmpty() || secret.get().isBlank()) {
            LOG.warn("Turnstile enabled but secret missing → reject (fail-closed)");
            return false;
        }

        String key = sha256(token);
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.isFresh(now)) {
            return hit.verified();
        }
        if (cache.size() >= CACHE_MAX) shrinkCache(now);

        boolean ok;
        try {
            ok = doVerify(token, remoteIp);
        } catch (Exception e) {
            LOG.warnf("Turnstile verify call failed: %s → reject (fail-closed)", e.getMessage());
            return false;
        }
        cache.put(key, new CacheEntry(now, ok));
        return ok;
    }

    /**
     * R32 P0 fix：async-friendly variant for callers on the event loop.
     *
     * <p>Returns a {@link CompletionStage} that completes with the
     * verification result without blocking. Use this from any reactive
     * pipeline (Mutiny, CompletionStage chain, Vert.x handler) to avoid
     * pinning the reactor thread while waiting for cf siteverify.
     *
     * <p>Behaviour mirrors {@link #verify(String, String)}:
     * <ul>
     *   <li>disabled → {@code completedFuture(true)} immediately</li>
     *   <li>missing token / secret → {@code completedFuture(false)}</li>
     *   <li>cache hit → {@code completedFuture(hit.verified())}</li>
     *   <li>cf miss → underlying WebClient call is already async; just
     *       chain its CompletableFuture instead of blocking on it</li>
     * </ul>
     */
    public java.util.concurrent.CompletionStage<Boolean> verifyAsync(String token, String remoteIp) {
        if (!enabled) return java.util.concurrent.CompletableFuture.completedFuture(true);
        if (token == null || token.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        if (secret.isEmpty() || secret.get().isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }

        String key = sha256(token);
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.isFresh(now)) {
            return java.util.concurrent.CompletableFuture.completedFuture(hit.verified());
        }
        if (cache.size() >= CACHE_MAX) shrinkCache(now);

        return doVerifyAsync(token, remoteIp)
            .handle((ok, ex) -> {
                if (ex != null) {
                    LOG.warnf("Turnstile verifyAsync failed: %s → reject (fail-closed)",
                        ex.getMessage());
                    return Boolean.FALSE;
                }
                cache.put(key, new CacheEntry(now, Boolean.TRUE.equals(ok)));
                return ok;
            });
    }

    private java.util.concurrent.CompletableFuture<Boolean> doVerifyAsync(String token, String remoteIp) {
        String form;
        try {
            form = buildVerifyForm(token, remoteIp);
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        java.net.URI uri = java.net.URI.create(CF_VERIFY_URL);
        boolean ssl = "https".equals(uri.getScheme());
        int port = uri.getPort() == -1 ? 443 : uri.getPort();

        sharedWebClient.client()
            .post(port, uri.getHost(), uri.getPath())
            .ssl(ssl)
            .timeout(timeoutMs)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .sendBuffer(io.vertx.core.buffer.Buffer.buffer(form))
            .onSuccess(resp -> {
                try {
                    if (resp.statusCode() != 200) {
                        future.complete(false);
                        return;
                    }
                    JsonObject body = resp.bodyAsJsonObject();
                    boolean success = body.getBoolean("success", false);
                    future.complete(success);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);
        return future;
    }

    private String buildVerifyForm(String token, String remoteIp) {
        return "secret=" + java.net.URLEncoder.encode(secret.get(),
            java.nio.charset.StandardCharsets.UTF_8)
            + "&response=" + java.net.URLEncoder.encode(token,
                java.nio.charset.StandardCharsets.UTF_8)
            + (remoteIp == null || remoteIp.isBlank() ? ""
                : "&remoteip=" + java.net.URLEncoder.encode(remoteIp,
                    java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean doVerify(String token, String remoteIp) throws Exception {
        // cf 端约定：application/x-www-form-urlencoded，POST。
        String form = "secret=" + java.net.URLEncoder.encode(secret.get(),
            java.nio.charset.StandardCharsets.UTF_8)
            + "&response=" + java.net.URLEncoder.encode(token,
                java.nio.charset.StandardCharsets.UTF_8)
            + (remoteIp == null || remoteIp.isBlank() ? ""
                : "&remoteip=" + java.net.URLEncoder.encode(remoteIp,
                    java.nio.charset.StandardCharsets.UTF_8));

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        java.net.URI uri = java.net.URI.create(CF_VERIFY_URL);
        boolean ssl = "https".equals(uri.getScheme());
        int port = uri.getPort() == -1 ? 443 : uri.getPort();

        sharedWebClient.client()
            .post(port, uri.getHost(), uri.getPath())
            .ssl(ssl)
            .timeout(timeoutMs)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .sendBuffer(io.vertx.core.buffer.Buffer.buffer(form))
            .onSuccess(resp -> {
                try {
                    if (resp.statusCode() != 200) {
                        future.complete(false);
                        return;
                    }
                    JsonObject body = resp.bodyAsJsonObject();
                    boolean success = body.getBoolean("success", false);
                    if (!success) {
                        LOG.debugf("Turnstile rejected token: codes=%s",
                            body.getJsonArray("error-codes"));
                    }
                    future.complete(success);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            })
            .onFailure(future::completeExceptionally);

        return future.get(timeoutMs + 500L, TimeUnit.MILLISECONDS);
    }

    /** 缓存达上限时清最旧一半，避免无限增长。 */
    private void shrinkCache(long nowMs) {
        cache.entrySet().removeIf(e -> !e.getValue().isFresh(nowMs));
        if (cache.size() < CACHE_MAX) return;
        // 还是超 → 强制清半数（任意顺序）
        int target = CACHE_MAX / 2;
        var it = cache.keySet().iterator();
        while (it.hasNext() && cache.size() > target) {
            it.next();
            it.remove();
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JLS 强制；fallback 也只用于 cache key，不影响安全。
            return String.valueOf(s.hashCode());
        }
    }
}
