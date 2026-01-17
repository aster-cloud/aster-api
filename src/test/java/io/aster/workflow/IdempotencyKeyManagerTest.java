package io.aster.workflow;

import io.aster.policy.test.RedisEnabledTest;
import jakarta.inject.Inject;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@RedisEnabledTest
class IdempotencyKeyManagerTest {

    @Inject
    IdempotencyKeyManager manager;

    @Test
    void testFirstAcquisitionSucceeds() {
        String key = "test-key-first";
        Optional<String> result = manager.tryAcquire(key, "entity-1", Duration.ofMinutes(5));
        assertTrue(result.isEmpty(), "首次获取应当成功");
        manager.release(key);
    }

    @Test
    void testDuplicateKeyReturnsExisting() {
        String key = "test-key-dup";
        Optional<String> first = manager.tryAcquire(key, "entity-1", Duration.ofMinutes(5));
        assertTrue(first.isEmpty(), "首次获取应当成功");

        Optional<String> second = manager.tryAcquire(key, "entity-2", Duration.ofMinutes(5));
        assertTrue(second.isPresent(), "重复键需要返回现有实体 ID");
        assertEquals("entity-1", second.get());
        manager.release(key);
    }

    @Test
    void testConcurrentAcquisitionRace() throws Exception {
        String key = "test-key-concurrent";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            String entityId = "entity-" + i;
            executor.submit(() -> {
                try {
                    Optional<String> acquired = manager.tryAcquire(key, entityId, Duration.ofMinutes(5));
                    results.put(entityId, acquired.isEmpty());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long successCount = results.values().stream().filter(Boolean::booleanValue).count();
        assertEquals(1, successCount, "只应当有一个线程成功获取幂等性键");
        manager.release(key);
    }
}
