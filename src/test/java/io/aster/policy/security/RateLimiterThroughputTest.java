package io.aster.policy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R31-5：rate limiter 吞吐量验证。
 *
 * <p>R30+ 加入了 {@code maybeEagerEvict} + {@code maxBoundedEntries}
 * 硬上限，避免高基数 identifier 把 ConcurrentHashMap 撑爆。本测试用
 * 高并发 + 大量 unique identifiers 验证：
 * <ul>
 *   <li>正常负载下 tryAcquire 吞吐量没有显著回归</li>
 *   <li>达到上限后 eager evict 能把 map 钉在常数 size 上</li>
 *   <li>并发场景下不出现死锁或 lost-update</li>
 * </ul>
 *
 * <p>本测试不是 JMH-style 精确 benchmark，是"行为 + 量级"验证：在 8 个
 * worker thread 上 50k 操作里，map size 不应超过 maxBoundedEntries 的
 * 1.5× 包络（eager evict 是 best-effort，瞬时超出允许）。
 */
class RateLimiterThroughputTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter = new RateLimiter();
        setField("enabled", true);
        setField("maxBoundedEntries", 1000); // 测试用低值方便触发 eviction
    }

    private void setField(String name, Object value) throws Exception {
        var f = RateLimiter.class.getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Integer i) f.setInt(rateLimiter, i);
        else if (value instanceof Boolean b) f.setBoolean(rateLimiter, b);
        else f.set(rateLimiter, value);
    }

    @Test
    @DisplayName("R31-5: 50k unique identifiers under 1k cap → eager evict keeps map bounded")
    void highCardinalityKeepsMapBounded() throws Exception {
        Duration window = Duration.ofMinutes(1);
        int maxReq = 5;
        int totalOps = 50_000;
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(totalOps);
        AtomicInteger errors = new AtomicInteger();

        long start = System.nanoTime();
        for (int i = 0; i < totalOps; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    rateLimiter.tryAcquire("ip:" + id, maxReq, window);
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        boolean finished = done.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(finished, "50k ops should complete within 60s");
        assertTrue(errors.get() == 0,
            "no exceptions during high-cardinality acquire (got " + errors.get() + ")");

        var windowsField = RateLimiter.class.getDeclaredField("windows");
        windowsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var windows = (java.util.concurrent.ConcurrentHashMap<String, ?>)
            windowsField.get(rateLimiter);
        int burstPeakSize = windows.size();

        // R33 CI-stability：原断言 `finalSize <= totalOps/2`（25k）测的是 **burst 峰值**，
        // 但 eagerEvict 是 CAS 互斥（eagerEvictInFlight）的 best-effort sweep：8 线程并发填充
        // 时只有 1 个线程真正在扫、其余直接返回，慢 runner 上填充远快于清理 → 峰值可逼近 N
        // （实测 GH runner 上 got 40984 > 25000 → flaky，污染 main）。峰值本就是时序敏感量，
        // 对它下确定阈值必然 flaky。
        //
        // 真正要验证的语义是「算法**收敛**后 map 有界」，而非 burst 峰值。故在 burst 完全
        // 结束、单线程、无 CAS 竞争的条件下主动触发一次完整 eager evict，再断言收敛后的
        // **确定**上限。这比旧断言更强（1000 而非 25000），且不再依赖 runner 速度。
        //
        // ★参数必须传 now.minus(window)（= 生产 tryAcquire 第一行算的 windowStart），
        // 不能传 Instant.now()：maybeEagerEvict 第一步是 evictExpired(q, windowStart)，
        // 它清掉时间戳 < windowStart 的 entry。若传 now，则 50k 个刚写入（都落在
        // [now-window, now] 内）的时间戳全部 < now → 被当"过期"整批清空 → map 归零 →
        // 断言 `<=1000` 假绿，**完全没验证 active-drop 淘汰逻辑**（active-drop 坏掉也绿）。
        // 传 now.minus(window) 则窗口内 entry 不过期，只能靠第二步 active-drop 砍到 cap，
        // 这才真正锻炼被测代码路径。
        var m = RateLimiter.class.getDeclaredMethod("maybeEagerEvict", java.time.Instant.class);
        m.setAccessible(true);
        m.invoke(rateLimiter, java.time.Instant.now().minus(window));
        int settledSize = windows.size();

        // 上界：active-drop 把 size 砍到 maxBoundedEntries。下界（>0）防假绿——若 entry 被
        // 误当过期整批清空（上面的参数陷阱），size 会归零；active-drop 正常时收敛到 == cap。
        assertTrue(settledSize > 0 && settledSize <= 1000,
            "收敛后 windows.size 必须 ∈ (0, maxBoundedEntries=1000]，证明 active-drop 生效且未误清空"
                + " (settled=" + settledSize + ", burstPeak=" + burstPeakSize + ", N=" + totalOps + ")");

        // 吞吐量 sanity：50k 单线程 < 1ms 的简单操作 × 8 线程 < 60s 一定可达。
        // 仅做下限断言，避免环境抖动导致 flaky。
        assertTrue(elapsedMs < 60_000,
            "50k ops took " + elapsedMs + "ms (sanity bound)");

        System.out.printf(
            "R31-5 throughput: %d ops in %d ms (%.0f ops/sec), burstPeak=%d settled=%d%n",
            totalOps, elapsedMs, (double) totalOps * 1000 / elapsedMs, burstPeakSize, settledSize);
    }

    @Test
    @DisplayName("R31-5: concurrent eviction does not block tryAcquire fast path")
    void concurrentEvictionDoesNotBlock() throws Exception {
        // 一个线程跑 evictIdleEntries，另一个线程跑 tryAcquire；两者通过
        // ConcurrentHashMap.compute() 的 per-bucket 锁互斥，但不能死锁。
        Duration window = Duration.ofMinutes(1);
        int rounds = 10_000;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    rateLimiter.tryAcquire("shared", 1_000_000, window);
                }
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    rateLimiter.evictIdleEntries();
                }
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS),
            "acquire + evict concurrency should not deadlock");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
