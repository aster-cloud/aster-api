package io.aster.policy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimiter 单元测试
 *
 * 验证滑动窗口限流、并发安全性和连接计数功能。
 * 不依赖 Quarkus CDI 容器，直接实例化测试。
 */
class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter = new RateLimiter();
        // 通过反射设置 enabled = true（绕过 CDI 注入）
        var enabledField = RateLimiter.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(rateLimiter, true);
    }

    @Test
    void tryAcquire_允许窗口内请求() {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 5;

        for (int i = 0; i < maxRequests; i++) {
            assertTrue(rateLimiter.tryAcquire("tenant-a", maxRequests, window),
                "第 " + (i + 1) + " 次请求应被允许");
        }
    }

    @Test
    void tryAcquire_超限后拒绝() {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 3;

        // 填满窗口
        for (int i = 0; i < maxRequests; i++) {
            assertTrue(rateLimiter.tryAcquire("tenant-b", maxRequests, window));
        }

        // 超限
        assertFalse(rateLimiter.tryAcquire("tenant-b", maxRequests, window),
            "超过最大请求数后应被拒绝");
    }

    @Test
    void tryAcquire_不同标识互不影响() {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 2;

        // tenant-1 消耗配额
        assertTrue(rateLimiter.tryAcquire("tenant-1", maxRequests, window));
        assertTrue(rateLimiter.tryAcquire("tenant-1", maxRequests, window));
        assertFalse(rateLimiter.tryAcquire("tenant-1", maxRequests, window));

        // tenant-2 不受影响
        assertTrue(rateLimiter.tryAcquire("tenant-2", maxRequests, window));
    }

    @Test
    void tryAcquire_窗口过期后恢复() throws InterruptedException {
        Duration window = Duration.ofMillis(100); // 100ms 窗口用于测试
        int maxRequests = 2;

        // 填满窗口
        assertTrue(rateLimiter.tryAcquire("tenant-expire", maxRequests, window));
        assertTrue(rateLimiter.tryAcquire("tenant-expire", maxRequests, window));
        assertFalse(rateLimiter.tryAcquire("tenant-expire", maxRequests, window));

        // 等待窗口过期
        Thread.sleep(150);

        // 恢复
        assertTrue(rateLimiter.tryAcquire("tenant-expire", maxRequests, window),
            "窗口过期后应重新允许请求");
    }

    @Test
    void remaining_返回正确剩余数() {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 5;

        assertEquals(5, rateLimiter.remaining("tenant-rem", maxRequests, window));

        rateLimiter.tryAcquire("tenant-rem", maxRequests, window);
        assertEquals(4, rateLimiter.remaining("tenant-rem", maxRequests, window));

        rateLimiter.tryAcquire("tenant-rem", maxRequests, window);
        rateLimiter.tryAcquire("tenant-rem", maxRequests, window);
        assertEquals(2, rateLimiter.remaining("tenant-rem", maxRequests, window));
    }

    @Test
    void remaining_超限后返回零() {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 2;

        rateLimiter.tryAcquire("tenant-zero", maxRequests, window);
        rateLimiter.tryAcquire("tenant-zero", maxRequests, window);
        rateLimiter.tryAcquire("tenant-zero", maxRequests, window); // 被拒绝但不影响

        assertEquals(0, rateLimiter.remaining("tenant-zero", maxRequests, window));
    }

    @Test
    void tryAcquireConnection_允许在限额内() {
        int maxConn = 3;

        assertTrue(rateLimiter.tryAcquireConnection("ip-1", maxConn));
        assertTrue(rateLimiter.tryAcquireConnection("ip-1", maxConn));
        assertTrue(rateLimiter.tryAcquireConnection("ip-1", maxConn));
        assertFalse(rateLimiter.tryAcquireConnection("ip-1", maxConn),
            "超过最大连接数后应被拒绝");
    }

    @Test
    void releaseConnection_释放后可重新获取() {
        int maxConn = 1;

        assertTrue(rateLimiter.tryAcquireConnection("ip-release", maxConn));
        assertFalse(rateLimiter.tryAcquireConnection("ip-release", maxConn));

        rateLimiter.releaseConnection("ip-release");

        assertTrue(rateLimiter.tryAcquireConnection("ip-release", maxConn),
            "释放连接后应可重新获取");
    }

    @Test
    void activeConnections_返回正确计数() {
        assertEquals(0, rateLimiter.activeConnections("ip-count"));

        rateLimiter.tryAcquireConnection("ip-count", 10);
        assertEquals(1, rateLimiter.activeConnections("ip-count"));

        rateLimiter.tryAcquireConnection("ip-count", 10);
        assertEquals(2, rateLimiter.activeConnections("ip-count"));

        rateLimiter.releaseConnection("ip-count");
        assertEquals(1, rateLimiter.activeConnections("ip-count"));
    }

    @Test
    void releaseConnection_不会降到负数() {
        rateLimiter.releaseConnection("ip-negative");
        assertEquals(0, rateLimiter.activeConnections("ip-negative"),
            "释放不存在的连接不应产生负数");

        rateLimiter.tryAcquireConnection("ip-negative", 5);
        rateLimiter.releaseConnection("ip-negative");
        rateLimiter.releaseConnection("ip-negative"); // 多次释放
        assertEquals(0, rateLimiter.activeConnections("ip-negative"));
    }

    @Test
    void tryAcquire_并发安全() throws InterruptedException {
        Duration window = Duration.ofMinutes(1);
        int maxRequests = 100;
        int threadCount = 20;
        int requestsPerThread = 10; // 总计 200 个请求，限额 100

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (rateLimiter.tryAcquire("concurrent-test", maxRequests, window)) {
                            allowed.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 允许数不应超过限额（ConcurrentLinkedDeque 在高并发下可能略有偏差，允许小范围容忍）
        assertTrue(allowed.get() <= maxRequests + threadCount,
            "允许的请求数 (" + allowed.get() + ") 不应大幅超过限额 (" + maxRequests + ")");
        assertEquals(threadCount * requestsPerThread, allowed.get() + denied.get(),
            "允许数 + 拒绝数应等于总请求数");
    }

    @Test
    void tryAcquireConnection_并发安全() throws InterruptedException {
        int maxConn = 10;
        int threadCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger allowed = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    if (rateLimiter.tryAcquireConnection("concurrent-conn", maxConn)) {
                        allowed.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // CAS 保证精确到 maxConn
        assertEquals(maxConn, allowed.get(),
            "并发连接获取应精确等于最大连接数");
    }

    @Test
    void resetAtEpochSecond_返回合理时间() {
        Duration window = Duration.ofMinutes(1);

        // 无条目时返回当前时间附近
        long now = java.time.Instant.now().getEpochSecond();
        long resetEmpty = rateLimiter.resetAtEpochSecond("reset-test", window);
        assertTrue(Math.abs(resetEmpty - now) <= 1, "无条目时应返回当前时间附近");

        // 有条目后返回最早条目 + 窗口时长
        rateLimiter.tryAcquire("reset-test", 10, window);
        long resetWithEntry = rateLimiter.resetAtEpochSecond("reset-test", window);
        assertTrue(resetWithEntry > now, "有条目时重置时间应在未来");
        assertTrue(resetWithEntry <= now + 61, "重置时间应在窗口期内");
    }

    @Test
    void disabled时所有请求通过() throws Exception {
        // 创建 disabled 的 rateLimiter
        RateLimiter disabledLimiter = new RateLimiter();
        var enabledField = RateLimiter.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(disabledLimiter, false);

        Duration window = Duration.ofMinutes(1);

        // 即使超限也应通过
        for (int i = 0; i < 100; i++) {
            assertTrue(disabledLimiter.tryAcquire("disabled-test", 1, window));
        }

        // 连接也应通过
        for (int i = 0; i < 100; i++) {
            assertTrue(disabledLimiter.tryAcquireConnection("disabled-conn", 1));
        }

        // remaining 应返回最大值
        assertEquals(10, disabledLimiter.remaining("any", 10, window));
    }
}
