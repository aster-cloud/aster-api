package io.aster.policy.security;

import io.vertx.core.net.SocketAddress;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单元测试覆盖 TrialEndpointGuard 的关键不变式：
 * <ol>
 *   <li>per-IP 分桶不串扰 + 滑动窗口原子性 + 并发槽位 release-on-exception。</li>
 *   <li>filter 实际行为：Origin allowlist、Content-Length / Transfer-Encoding 闸门、
 *       TRIAL_GUARD_PASSED_PROP 颁发时机、非 trial 路径放行、disabled 状态。</li>
 *   <li>XFF 信任开关：默认看 socket 远端地址；开启时优先 XFF。</li>
 * </ol>
 *
 * <p>RateLimiter 部分用真实 bean；ContainerRequestContext / RoutingContext 用
 * Mockito mock。这样可以验证 guard 的每一道闸门的边界行为，而不必拉起整个
 * Quarkus 上下文。
 */
class TrialEndpointGuardTest {

    // ============================================================
    // 1) RateLimiter 原子性 + 并发槽位
    // ============================================================

    @Test
    void perIpBucketsDoNotInterfere() {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;

        Duration minute = Duration.ofSeconds(60);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("trial:ip:1.2.3.4:1m", 10, minute),
                "IP A 应当能在分钟窗口内消耗 10 次额度");
        }
        assertFalse(limiter.tryAcquire("trial:ip:1.2.3.4:1m", 10, minute),
            "IP A 第 11 次必须被拒");
        assertTrue(limiter.tryAcquire("trial:ip:5.6.7.8:1m", 10, minute),
            "IP B 独立分桶，不受 IP A 限流影响");
    }

    @Test
    void minuteAndHourWindowsAreIndependent() {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;
        Duration minute = Duration.ofSeconds(60);
        Duration hour = Duration.ofSeconds(3600);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("trial:ip:9.9.9.9:1m", 10, minute));
            assertTrue(limiter.tryAcquire("trial:ip:9.9.9.9:1h", 60, hour));
        }
        assertFalse(limiter.tryAcquire("trial:ip:9.9.9.9:1m", 10, minute));
        assertEquals(50, limiter.remaining("trial:ip:9.9.9.9:1h", 60, hour),
            "hour 桶应该还剩 60-10=50 次");
    }

    @Test
    void concurrencySlotsReleaseAfterUse() {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;

        String key = "trial:ip:1.1.1.1";
        assertTrue(limiter.tryAcquireConnection(key, 2));
        assertTrue(limiter.tryAcquireConnection(key, 2));
        assertFalse(limiter.tryAcquireConnection(key, 2),
            "第 3 个并发请求必须被拒");

        limiter.releaseConnection(key);
        assertTrue(limiter.tryAcquireConnection(key, 2),
            "release 后槽位可复用");
    }

    /**
     * R-Round-2/3 残留：evictIdleEntries 与并发 tryAcquire 不应丢配额。
     *
     * 旧实现（computeIfAbsent + synchronized 在 bucket 锁外）下，evictor 可以
     * 在 tryAcquire 拿到 deque 之后、addLast 之前删掉 entry，下一次 acquire
     * 会重建一个空 deque，配额事实上被重置。
     *
     * 新实现（tryAcquire 整个事务在 windows.compute 内）下，acquire 与
     * evictIdleEntries 通过 ConcurrentHashMap 的 per-key bucket 锁互斥串行；
     * 任何 acquire/evict 交错都不会让单一 key 在同一窗口内放过超过 max 次。
     *
     * 不变式：N 次 tryAcquire 后 ok ≤ max，独立于 evictor 何时介入。
     */
    @Test
    void evictIdleEntriesDoesNotLoseTimestamps() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;
        Duration minute = Duration.ofSeconds(60);
        String key = "trial:ip:evict-race:1m";
        int max = 5;

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread evictor = new Thread(() -> {
            while (!stop.get()) {
                limiter.evictIdleEntries();
            }
        }, "evict-thread");
        evictor.setDaemon(true);
        evictor.start();

        int ok = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.tryAcquire(key, max, minute)) ok++;
        }
        stop.set(true);
        evictor.join();

        assertTrue(ok <= max,
            "evictIdleEntries 在并发场景下不应该让单一 key 在同一窗口内放过超过 max 个请求；"
                + "实际放过 " + ok + " / 上限 " + max);
    }

    /**
     * 强化版：多个工作线程持续 tryAcquire + 一个 evictor 持续清理，
     * 总放过数（跨多个工作线程）也不应超过 max。这检验 windows.compute
     * 内事务对 多个 acquire 线程 + 1 个 evict 线程 的三方互斥。
     *
     * 之前 round-3 reviewer 指出测试 "race window 太小"；这版本通过 16 个并发
     * worker × 50 次 acquire 把碰撞频次拉到几千次量级。evictor 用的是产品
     * 同款 evictIdleEntries（5 分钟 cutoff），因此本测试主要证明：在
     * "compute() 间事务串行化" 的前提下，无论 evict 怎么穿插也不会丢失
     * 时间戳。要测试"激进删除非空 deque"得直接 mock cutoff（产品不会这么做）。
     */
    @Test
    void evictIdleEntriesUnderHeavyAcquireContention() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;
        Duration window = Duration.ofMinutes(60); // 长窗口：天然不会过期
        String key = "trial:ip:heavy:1h";
        int max = 8;
        int workers = 16;
        int perWorker = 50;

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread evictor = new Thread(() -> {
            while (!stop.get()) {
                limiter.evictIdleEntries();
            }
        }, "evict-heavy");
        evictor.setDaemon(true);
        evictor.start();

        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int j = 0; j < perWorker; j++) {
                    if (limiter.tryAcquire(key, max, window)) ok.incrementAndGet();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) t.join();
        stop.set(true);
        evictor.join();

        assertTrue(ok.get() <= max,
            "多线程 acquire + 并发 evict 场景下，单一 key 在同一窗口内放过的总数 ("
                + ok.get() + ") 不应超过 max=" + max);
    }

    /**
     * 并发场景下 tryAcquire 必须原子：32 个线程对同一个 key 同时争 max=10 个名额，
     * 应当恰好有 10 个 true、22 个 false。原子化前这里会出现 11+ 个 true。
     */
    @Test
    void slidingWindowIsAtomicUnderConcurrentLoad() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.enabled = true;
        Duration minute = Duration.ofSeconds(60);
        String key = "trial:ip:concurrent:1m";
        int threads = 32;
        int max = 10;

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger ko = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (limiter.tryAcquire(key, max, minute)) ok.incrementAndGet();
                else ko.incrementAndGet();
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) t.join();

        assertEquals(max, ok.get(),
            "并发场景下命中数必须 = max；当前: " + ok.get() + " ok / " + ko.get() + " ko");
        assertEquals(threads - max, ko.get(),
            "未命中数应当等于 threads - max");
    }

    // ============================================================
    // 2) Filter 行为：Origin / CL / TE / property 颁发 / 路径匹配
    // ============================================================

    private TrialEndpointGuard guardWith(boolean enabled, boolean trustXff) {
        TrialEndpointGuard g = new TrialEndpointGuard();
        g.rateLimiter = new RateLimiter();
        g.rateLimiter.enabled = true;
        g.enabled = enabled;
        g.allowedOriginsCsv = "https://aster-lang.dev,https://aster-lang.cloud";
        g.maxBodyBytes = 32768;
        g.perIpMinuteMax = 10;
        g.perIpHourMax = 60;
        g.perIpConcurrentMax = 2;
        g.trustForwardedFor = trustXff;
        return g;
    }

    private ContainerRequestContext mockCtx(String path, String method,
                                            Map<String, String> headers,
                                            Map<String, Object> propsSink) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getMethod()).thenReturn(method);
        // 用 anyString() 一次性映射所有 header 查询 —— Mockito last-wins，
        // 用 single Answer 比逐 key stub 更稳。
        lenient().when(ctx.getHeaderString(anyString())).thenAnswer(
            inv -> headers.get(inv.getArgument(0, String.class)));
        // property storage
        org.mockito.stubbing.Answer<Void> setter = inv -> {
            propsSink.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        };
        lenient().doAnswer(setter).when(ctx).setProperty(anyString(), any());
        lenient().when(ctx.getProperty(anyString())).thenAnswer(
            inv -> propsSink.get(inv.getArgument(0, String.class)));
        return ctx;
    }

    private void setRouting(TrialEndpointGuard g, String remoteHost) {
        RoutingContext r = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        SocketAddress addr = mock(SocketAddress.class);
        lenient().when(addr.host()).thenReturn(remoteHost);
        lenient().when(req.remoteAddress()).thenReturn(addr);
        lenient().when(r.request()).thenReturn(req);
        g.routingContext = r;
    }

    @Test
    void disabledGuardIsNoOp() {
        TrialEndpointGuard g = guardWith(false, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://evil.example.com"),
            props);
        assertNull(g.filterRequest(ctx),
            "enabled=false 时所有请求都放过");
        assertNull(props.get(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP));
    }

    @Test
    void nonTrialPathIsIgnored() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate", "POST", Map.of(), props);
        assertNull(g.filterRequest(ctx),
            "非 evaluate-source 路径不应被 trial guard 干涉");
        assertNull(props.get(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP));
    }

    @Test
    void optionsPreflightIsIgnored() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "OPTIONS",
            Map.of("Origin", "https://evil.example.com"),
            props);
        assertNull(g.filterRequest(ctx),
            "OPTIONS preflight 走 CorsFilter，不该被 trial guard 拦");
    }

    @Test
    void disallowedOriginIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://evil.example.com",
                "Content-Length", "100"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(403, r.getStatus());
        assertNull(props.get(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP));
    }

    @Test
    void missingOriginIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Content-Length", "100"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(403, r.getStatus());
    }

    @Test
    void chunkedTransferEncodingIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Transfer-Encoding", "chunked"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(413, r.getStatus());
    }

    @Test
    void missingContentLengthIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(411, r.getStatus());
    }

    @Test
    void invalidContentLengthIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "not-a-number"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(411, r.getStatus());
    }

    @Test
    void overSizedBodyIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "999999"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(413, r.getStatus());
    }

    @Test
    void negativeContentLengthIsRejected() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "-5"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(413, r.getStatus());
    }

    @Test
    void validRequestPassesAndSetsGuardProperty() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "256"),
            props);
        Response r = g.filterRequest(ctx);
        assertNull(r, "合法 trial 请求应当放过");
        assertEquals(Boolean.TRUE, props.get(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP),
            "guard 通过后必须颁发 TRIAL_GUARD_PASSED_PROP=true，下游 filter 据此放过 HMAC");
    }

    @Test
    void rateLimitExhaustionReturns429() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        // 把 minute 上限调到 2，方便耗尽
        g.perIpMinuteMax = 2;

        for (int i = 0; i < 2; i++) {
            Map<String, Object> props = new HashMap<>();
            ContainerRequestContext ctx = mockCtx(
                "/api/v1/policies/evaluate-source", "POST",
                Map.of("Origin", "https://aster-lang.dev",
                    "Content-Length", "100"),
                props);
            // 第 1、2 个请求合法；同时占了一个 connection slot —— 必须 release，
            // 否则后续测试会卡在并发槽位上而非 rate 上。
            assertNull(g.filterRequest(ctx));
            String slotKey = "trial:ip:10.0.0.1";
            g.rateLimiter.releaseConnection(slotKey);
        }
        // 第 3 次 → 429（rate-limit-minute）
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100"),
            props);
        Response r = g.filterRequest(ctx);
        assertNotNull(r);
        assertEquals(429, r.getStatus());
        assertNotNull(r.getHeaderString("Retry-After"));
    }

    @Test
    void concurrencyExhaustionReturns429() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        g.perIpConcurrentMax = 1;

        Map<String, Object> p1 = new HashMap<>();
        ContainerRequestContext c1 = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100"),
            p1);
        assertNull(g.filterRequest(c1), "第 1 个并发请求被允许");

        Map<String, Object> p2 = new HashMap<>();
        ContainerRequestContext c2 = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100"),
            p2);
        Response r2 = g.filterRequest(c2);
        assertNotNull(r2);
        assertEquals(429, r2.getStatus(),
            "第 2 个并发请求（concurrent-max=1）应返回 429");
    }

    // ============================================================
    // 3) Response filter 释放连接槽位（包括异常路径）
    // ============================================================

    @Test
    void responseFilterReleasesSlotEvenAfterRequestException() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1");
        g.perIpConcurrentMax = 1;

        // 占用第 1 个 slot
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100"),
            props);
        assertNull(g.filterRequest(ctx));
        // 模拟下游抛错：直接进 response filter
        g.filterResponse(ctx);

        // 现在 slot 应该已 release，新请求可以申请
        Map<String, Object> p2 = new HashMap<>();
        ContainerRequestContext ctx2 = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100"),
            p2);
        // 重置 minute 桶上限以避免被 rate 拦
        g.perIpMinuteMax = 10;
        assertNull(g.filterRequest(ctx2),
            "response filter 应已释放 slot；新请求应被允许");
    }

    @Test
    void responseFilterIsNoOpWhenNoSlotWasHeld() {
        TrialEndpointGuard g = guardWith(true, false);
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(anyString())).thenAnswer(
            inv -> props.get(inv.getArgument(0, String.class)));
        // 没设过 SLOT_HOLDER_PROP；调用不应报错
        g.filterResponse(ctx);
    }

    // ============================================================
    // 4) XFF 信任开关
    // ============================================================

    @Test
    void xffIgnoredByDefault() {
        TrialEndpointGuard g = guardWith(true, false);
        setRouting(g, "10.0.0.1"); // 真 socket IP
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100",
                "X-Forwarded-For", "1.2.3.4"),
            props);

        String ip = TrialEndpointGuard.clientIp(ctx, g.routingContext, false);
        assertEquals("10.0.0.1", ip, "默认 trust-forwarded-for=false 时必须忽略 XFF");
    }

    @Test
    void xffHonoredWhenTrustEnabled() {
        TrialEndpointGuard g = guardWith(true, true);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100",
                "X-Forwarded-For", "1.2.3.4, 10.0.0.1"),
            props);
        String ip = TrialEndpointGuard.clientIp(ctx, g.routingContext, true);
        assertEquals("1.2.3.4", ip, "trust-forwarded-for=true 时 XFF 第一段优先");
    }

    @Test
    void xrealIpHonoredWhenTrustEnabledAndXffMissing() {
        TrialEndpointGuard g = guardWith(true, true);
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.dev",
                "Content-Length", "100",
                "X-Real-IP", "5.6.7.8"),
            props);
        String ip = TrialEndpointGuard.clientIp(ctx, g.routingContext, true);
        assertEquals("5.6.7.8", ip);
    }

    @Test
    void clientIpFallsBackToUnknownWhenAllSourcesMissing() {
        TrialEndpointGuard g = guardWith(true, true);
        // routingContext = null 模拟极端 fallback
        g.routingContext = null;
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of(), props);
        String ip = TrialEndpointGuard.clientIp(ctx, g.routingContext, true);
        assertEquals("unknown", ip);
    }

    // ============================================================
    // 5) AllowedOrigins CSV 解析（init() 行为）
    // ============================================================

    @Test
    void emptyAllowedOriginsAllowsAnyOriginButLogsWarning() {
        // operator 把 allowed-origins 设空表示"接受任意 Origin"。生产 anti-pattern，
        // 但代码必须不爆。
        TrialEndpointGuard g = guardWith(true, false);
        g.allowedOriginsCsv = "";
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://anything.example",
                "Content-Length", "100"),
            props);
        Response r = g.filterRequest(ctx);
        assertNull(r, "空 allowlist 时不做 Origin 校验");
        assertEquals(Boolean.TRUE, props.get(TrialEndpointGuard.TRIAL_GUARD_PASSED_PROP));
    }

    @Test
    void csvParsedTrimWhitespaceAndEmpty() {
        TrialEndpointGuard g = guardWith(true, false);
        g.allowedOriginsCsv = "  https://aster-lang.dev , , https://aster-lang.cloud  ";
        setRouting(g, "10.0.0.1");
        Map<String, Object> props = new HashMap<>();
        ContainerRequestContext ctx = mockCtx(
            "/api/v1/policies/evaluate-source", "POST",
            Map.of("Origin", "https://aster-lang.cloud",
                "Content-Length", "100"),
            props);
        assertNull(g.filterRequest(ctx),
            "Origin allowlist 应正确处理逗号 + 空白 + 空段");
    }
}
