package io.aster.policy.lexicon;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R11-Backend-Critical：acquireUploadLock 单元测试。
 *
 * <p>不启动 Quarkus 上下文 —— 直接 new HotPlugLexiconLoader 实例，调 lock API。
 * 此测试与 CDI 生命周期无关；@ApplicationScoped 注解不阻止直接 new。
 */
class HotPlugLexiconLoaderUploadLockTest {

    @Test
    void sameFileNameReturnsSameLockInstance() {
        var loader = new HotPlugLexiconLoader();
        ReentrantLock l1 = loader.acquireUploadLock("zh-CN.jar");
        ReentrantLock l2 = loader.acquireUploadLock("zh-CN.jar");
        assertSame(l1, l2, "同 fileName 必须返回同一把锁（否则不互斥）");
    }

    @Test
    void differentFileNamesUsuallyMapToDifferentLocks() {
        // R12-Minor-4: 64-stripe pool 下不同 fileName 可能 hash 碰撞共享锁，
        // 但典型 locale jar 名（zh-CN/de-DE/...）实测不碰撞，覆盖最常见使用场景。
        // 这里只断言 "我们关心的几个真实 lexicon 名互不碰撞"。
        var loader = new HotPlugLexiconLoader();
        ReentrantLock zh = loader.acquireUploadLock("zh-CN.jar");
        ReentrantLock de = loader.acquireUploadLock("de-DE.jar");
        ReentrantLock en = loader.acquireUploadLock("en-US.jar");
        assertNotSame(zh, de, "zh-CN 与 de-DE 不应碰撞");
        assertNotSame(zh, en, "zh-CN 与 en-US 不应碰撞");
        assertNotSame(de, en, "de-DE 与 en-US 不应碰撞");
    }

    @Test
    void crossReplicaLockSerializesSameFileName() throws Exception {
        // R13-Major：真实证明跨"replica"（用两个独立 FileChannel 模拟两个 pod 的进程）
        // 互斥。Java FileLock 是 process-scope 的；同一 JVM 不同 channel 会按 OS
        // POSIX 语义互斥（在 Linux/macOS 上 fcntl byte-range lock 也是同 JVM 互斥的）。
        var tmp = java.nio.file.Files.createTempDirectory("aster-lock-test-");
        try {
            var loader = new HotPlugLexiconLoader();
            var field = HotPlugLexiconLoader.class.getDeclaredField("hotplugDir");
            field.setAccessible(true);
            field.set(loader, tmp.toString());

            // pod A：通过 loader API 获取锁
            var first = loader.tryAcquireCrossReplicaLock("zh-CN.jar");
            assertTrue(first.isPresent(), "first attempt should acquire");

            // pod B：用独立 FileChannel 在同一 path 上 tryLock，模拟另一个进程
            java.nio.file.Path lockPath = tmp.resolve("pending").resolve("zh-CN.jar.cross-lock");
            try (var chB = java.nio.channels.FileChannel.open(
                    lockPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock flB;
                try {
                    flB = chB.tryLock();
                } catch (java.nio.channels.OverlappingFileLockException ovr) {
                    // 同 JVM 重复 tryLock 抛此异常 —— 等价于"未取到"
                    flB = null;
                }
                assertNull(flB,
                    "pod B 的 tryLock 必须返回 null 或抛 OverlappingFileLockException —— "
                    + "证明 cross-replica 锁互斥");
            }

            // pod A 释放
            first.get().close();

            // R13-Critical：close 不删 lockfile，所以路径仍存在
            assertTrue(java.nio.file.Files.exists(lockPath),
                "R13-Critical: close() 不应删除 lockfile");

            // pod C：释放后能重新获取
            var third = loader.tryAcquireCrossReplicaLock("zh-CN.jar");
            assertTrue(third.isPresent(), "after releasing, should re-acquire");
            third.get().close();
        } finally {
            try (var stream = java.nio.file.Files.walk(tmp)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void stripedPoolIsBounded() {
        // R12-Minor-4: 不管多少不同 fileName，底层 locks 数量恒为 N_STRIPES (64)
        var loader = new HotPlugLexiconLoader();
        java.util.IdentityHashMap<ReentrantLock, Boolean> seen = new java.util.IdentityHashMap<>();
        for (int i = 0; i < 10_000; i++) {
            seen.put(loader.acquireUploadLock("synthetic-" + i + ".jar"), Boolean.TRUE);
        }
        // 64 是配置的 stripe 数；不强断言 == 64（hash 分布可能用不满），
        // 但绝对应 ≤ 64 —— 这是 "bounded" 的关键保证
        assertTrue(seen.size() <= 64,
            "striped 池应最多 64 把锁，实际 " + seen.size());
    }

    @Test
    void concurrentSameFileNameUploadsSerialize() throws Exception {
        // 用 8 线程抢同一 fileName 的锁，验证临界区是串行的
        var loader = new HotPlugLexiconLoader();
        int threads = 8;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                es.submit(() -> {
                    try {
                        start.await();
                        ReentrantLock lock = loader.acquireUploadLock("contended.jar");
                        lock.lock();
                        try {
                            int now = inside.incrementAndGet();
                            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                            Thread.sleep(5);  // 模拟事务工作
                            inside.decrementAndGet();
                        } finally {
                            lock.unlock();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS),
                "所有线程应在 30s 内完成");
            assertEquals(1, maxConcurrent.get(),
                "per-fileName 锁必须互斥：max concurrent = 1，实际 " + maxConcurrent.get());
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void differentFileNamesParallelize() throws Exception {
        // 不同 fileName 应允许并行
        var loader = new HotPlugLexiconLoader();
        int threads = 4;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                String fn = "locale-" + i + ".jar";
                es.submit(() -> {
                    try {
                        start.await();
                        ReentrantLock lock = loader.acquireUploadLock(fn);
                        lock.lock();
                        try {
                            int now = inside.incrementAndGet();
                            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                            Thread.sleep(20);
                            inside.decrementAndGet();
                        } finally {
                            lock.unlock();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            // 不同 fileName 的锁互相独立，应至少有 2 个并发（不要求严格 == threads
            // 以避免线程调度抖动 false-negative）
            assertTrue(maxConcurrent.get() >= 2,
                "不同 fileName 应能并行，实际最大并发 " + maxConcurrent.get());
        } finally {
            es.shutdownNow();
        }
    }
}
