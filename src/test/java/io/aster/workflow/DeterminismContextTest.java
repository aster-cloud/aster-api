package io.aster.workflow;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证 DeterminismContext 在多线程环境中的隔离性，确保 ThreadLocal 不泄漏状态。
 */
class DeterminismContextTest {

    @Test
    void testThreadLocalIsolation() throws Exception {
        // 使用固定线程池模拟并发 workflow 执行
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> {
                DeterminismContext context = new DeterminismContext();
                return context.uuid().randomUUID().toString();
            }, executor);

            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> {
                DeterminismContext context = new DeterminismContext();
                return context.uuid().randomUUID().toString();
            }, executor);

            String uuid1 = f1.get(5, TimeUnit.SECONDS);
            String uuid2 = f2.get(5, TimeUnit.SECONDS);

            assertNotEquals(uuid1, uuid2, "不同线程应各自获得独立 DeterminismContext");
        } finally {
            executor.shutdownNow();
        }
    }
}
