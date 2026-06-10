package io.aster.policy.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /evaluate-source 背压（bounded concurrency）契约的负载验证。
 *
 * <p>背景：{@code PolicyEvaluationResource} 用一个 fair {@link Semaphore}
 * 给 /evaluate-source 设并发上限——每个 in-flight 调用持有一个 Polyglot
 * Context（~30–50MB 瞬态状态），无上限并发会在 burst 下把 JVM 打到 OOM。
 * 端点用 {@code tryAcquire(timeout)} 在 250ms 窗口内抢 permit，抢不到就
 * 返回 503 + Retry-After 让调用方退避，而不是堆积请求拖垮 worker pool。
 *
 * <p>本测试不启动 Quarkus，而是直接对端点所用的<b>同一背压算法</b>
 * （fair Semaphore + tryAcquire 超时 + try/finally 释放）施加超额并发，
 * 验证四条关键不变量——这些不变量一旦破坏，生产就会回到 OOM 老路：
 * <ol>
 *   <li><b>不过度准入</b>：任一时刻同时持有 permit 的请求数 ≤ 容量上限</li>
 *   <li><b>饱和即拒绝</b>：超额请求在超时窗口内抢不到 permit → 走 503 分支</li>
 *   <li><b>不泄漏 permit</b>：所有持有者释放后，可用 permit 完全恢复到上限</li>
 *   <li><b>释放即恢复</b>：持有者退出后，先前会被拒的请求能重新被准入</li>
 * </ol>
 *
 * <p>这是"行为 + 不变量"验证（对齐 {@link RateLimiterThroughputTest} 的风格），
 * 不是 JMH benchmark。
 */
class EvalSourceBackpressureTest {

    /** 端点常量的镜像：fair semaphore + 250ms 抢锁窗口（见 PolicyEvaluationResource）。 */
    private static final int CAPACITY = 2;
    private static final long ACQUIRE_TIMEOUT_MS = 250;

    /**
     * 模拟端点的 permit 获取语义：在超时窗口内抢到返回 true，否则 false（→ 503）。
     */
    private static boolean tryAdmit(Semaphore permits) throws InterruptedException {
        return permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("饱和：超额并发下，准入数不超过容量上限，其余被拒（503 路径）")
    void saturationSheddsExcessAndNeverOverAdmits() throws Exception {
        Semaphore permits = new Semaphore(CAPACITY, true);

        int concurrent = 16;               // 远超 CAPACITY=2
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        CountDownLatch holdAll = new CountDownLatch(1); // 让已准入者一起卡住，制造饱和
        CountDownLatch admittedReady = new CountDownLatch(CAPACITY);
        CountDownLatch done = new CountDownLatch(concurrent);

        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger concurrentHeld = new AtomicInteger();
        AtomicInteger maxConcurrentHeld = new AtomicInteger();

        for (int i = 0; i < concurrent; i++) {
            pool.submit(() -> {
                try {
                    if (tryAdmit(permits)) {
                        // 准入：进入临界区，记录同时持有数的峰值
                        int held = concurrentHeld.incrementAndGet();
                        maxConcurrentHeld.accumulateAndGet(held, Math::max);
                        admitted.incrementAndGet();
                        admittedReady.countDown();
                        try {
                            // 卡在临界区里直到测试放行——制造持续饱和
                            holdAll.await(10, TimeUnit.SECONDS);
                        } finally {
                            concurrentHeld.decrementAndGet();
                            permits.release(); // 端点用 onTermination 释放，这里 try/finally 等价
                        }
                    } else {
                        // 抢不到 → 端点这里返回 503 + Retry-After
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // 等到 CAPACITY 个请求确实进入临界区（饱和已形成）
        assertTrue(admittedReady.await(5, TimeUnit.SECONDS),
            "应有 " + CAPACITY + " 个请求被准入并进入临界区");
        // 此刻其余请求正在 250ms 窗口里抢锁——给足时间让它们全部超时被拒
        Thread.sleep(ACQUIRE_TIMEOUT_MS + 200);
        holdAll.countDown(); // 放行持有者
        assertTrue(done.await(15, TimeUnit.SECONDS), "所有请求应在 15s 内结束");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 不变量 1：任一时刻并发持有数从不超过容量上限
        assertTrue(maxConcurrentHeld.get() <= CAPACITY,
            "并发持有数峰值不得超过容量 " + CAPACITY + "（实测 " + maxConcurrentHeld.get() + "）");
        // 不变量 2：饱和期间超额请求必须被拒（走 503 分支），不能全部静默放行
        assertTrue(rejected.get() >= concurrent - CAPACITY - 1,
            "饱和期超额请求应被拒：concurrent=" + concurrent + " admitted=" + admitted.get()
                + " rejected=" + rejected.get());
        assertEquals(concurrent, admitted.get() + rejected.get(),
            "每个请求要么被准入要么被拒，无丢失");
        // 不变量 3：所有持有者释放后，permit 完全恢复（无泄漏）
        assertEquals(CAPACITY, permits.availablePermits(),
            "持有者全部释放后，可用 permit 应恢复到容量上限（permit 泄漏会 < " + CAPACITY + "）");

        System.out.printf(
            "backpressure saturation: concurrent=%d cap=%d → admitted=%d rejected=%d "
                + "maxHeld=%d permitsAfter=%d%n",
            concurrent, CAPACITY, admitted.get(), rejected.get(),
            maxConcurrentHeld.get(), permits.availablePermits());
    }

    @Test
    @DisplayName("恢复：持有者释放后，先前会被拒的请求重新被准入")
    void capacityRecoversAfterRelease() throws Exception {
        Semaphore permits = new Semaphore(CAPACITY, true);

        // 占满全部 permit
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(tryAdmit(permits), "初始 permit 应可抢到");
        }
        assertEquals(0, permits.availablePermits(), "permit 已被占满");

        // 饱和状态下新请求应被拒（超时窗口内抢不到）
        assertTrue(!tryAdmit(permits), "饱和时新请求应被拒（503 路径）");

        // 释放一个 permit，模拟一个 in-flight 请求完成
        permits.release();

        // 现在应能重新准入——证明释放即恢复，无死锁/无泄漏
        assertTrue(tryAdmit(permits), "释放后先前会被拒的请求应能重新被准入");

        // 收尾释放，验证最终无泄漏
        permits.release();
        permits.release();
        assertEquals(CAPACITY, permits.availablePermits(),
            "全部释放后 permit 恢复到容量上限");
    }

    @Test
    @DisplayName("公平性 + 无泄漏：高轮次 acquire/release 循环后 permit 精确归位")
    void fairSemaphoreNoLeakUnderChurn() throws Exception {
        Semaphore permits = new Semaphore(CAPACITY, true);
        int threads = 8;
        int roundsPerThread = 5_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger leaks = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int r = 0; r < roundsPerThread; r++) {
                        boolean got = false;
                        try {
                            got = permits.tryAcquire(); // 非阻塞抢锁
                            if (got) {
                                admitted.incrementAndGet();
                            } else {
                                rejected.incrementAndGet();
                            }
                        } finally {
                            if (got) {
                                permits.release();
                            }
                        }
                    }
                } catch (Throwable ex) {
                    leaks.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "churn 循环应在 30s 内完成（无死锁）");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(0, leaks.get(), "acquire/release 循环不应抛异常");
        // 关键不变量：无论被准入/被拒多少次，churn 结束后 permit 必须精确归位
        assertEquals(CAPACITY, permits.availablePermits(),
            "高轮次 churn 后 permit 必须精确恢复到容量上限（任何偏差 = 泄漏或重复释放）");
        assertEquals(threads * roundsPerThread, admitted.get() + rejected.get(),
            "每轮要么准入要么被拒，计数无丢失");

        System.out.printf(
            "backpressure churn: %d ops (admitted=%d rejected=%d) → permitsAfter=%d (cap=%d)%n",
            threads * roundsPerThread, admitted.get(), rejected.get(),
            permits.availablePermits(), CAPACITY);
    }
}
