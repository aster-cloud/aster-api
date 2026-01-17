package io.aster.audit.inbox;

import io.aster.test.PostgresTestResource;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.impl.ContextInternal;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InboxEvent 实体测试
 * 验证幂等插入、TTL 清理以及并发场景下的 UNIQUE 约束
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class InboxEventTest {

    @Inject
    Vertx vertx;

    @Inject
    Pool pgPool;

    @BeforeEach
    void cleanInboxBefore() {
        truncateInbox();
    }

    @AfterEach
    void cleanInboxAfter() {
        truncateInbox();
    }

    @Test
    void testTryInsertSuccess() {
        InboxEvent event = runOnVertx(() ->
            InboxEvent.tryInsert("idempotent-success", "POLICY_CREATE", "tenant-alpha", "{\"foo\": \"bar\"}")
        );

        assertThat(event).isNotNull();
        assertThat(event.eventType).isEqualTo("POLICY_CREATE");
        assertThat(event.tenantId).isEqualTo("tenant-alpha");

        Boolean exists = runOnVertx(() ->
            Panache.withSession(() -> InboxEvent.exists("idempotent-success"))
        );
        assertThat(exists).isTrue();
    }

    @Test
    void testTryInsertDuplicate() {
        String key = "duplicate-key";
        runOnVertx(() ->
            InboxEvent.tryInsert(key, "ANOMALY_UPDATE", "tenant-dup")
        );

        InboxEvent duplicate = runOnVertx(() ->
            InboxEvent.tryInsert(key, "ANOMALY_UPDATE", "tenant-dup")
        );

        assertThat(duplicate).isNull();
    }

    @Test
    void testCleanupOldEvents() {
        Instant now = Instant.now();
        persistCustomEvent("old-event", now.minus(14, ChronoUnit.DAYS));
        persistCustomEvent("recent-event", now.minus(2, ChronoUnit.DAYS));

        Long deleted = runOnVertx(() ->
            Panache.withTransaction(() -> InboxEvent.cleanupOldEvents(7))
        );
        assertThat(deleted).isEqualTo(1);

        Boolean oldExists = runOnVertx(() ->
            Panache.withSession(() -> InboxEvent.exists("old-event"))
        );
        Boolean recentExists = runOnVertx(() ->
            Panache.withSession(() -> InboxEvent.exists("recent-event"))
        );

        assertThat(oldExists).isFalse();
        assertThat(recentExists).isTrue();
    }

    @Test
    void testConcurrentInsert() throws Exception {
        String key = "concurrent-key";
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InboxEvent>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await(2, TimeUnit.SECONDS);
                return runOnVertx(() ->
                    InboxEvent.tryInsert(key, "POLICY_REPLAY", "tenant-concurrent")
                );
            }));
        }

        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        int successCount = 0;
        for (Future<InboxEvent> future : futures) {
            InboxEvent result = future.get(5, TimeUnit.SECONDS);
            if (result != null) {
                successCount++;
            }
        }
        executor.shutdownNow();

        assertThat(successCount).isEqualTo(1);

        Boolean exists = runOnVertx(() ->
            Panache.withSession(() -> InboxEvent.exists(key))
        );
        assertThat(exists).isTrue();
    }

    private void truncateInbox() {
        runOnVertx(() ->
            pgPool.query("DELETE FROM inbox_events")
                .execute()
                .replaceWith((Void) null)
        );
    }

    private InboxEvent persistCustomEvent(String key, Instant timestamp) {
        InboxEvent event = new InboxEvent();
        event.idempotencyKey = key;
        event.eventType = "POLICY_EVENT";
        event.tenantId = "tenant-cleanup";
        event.processedAt = timestamp;
        event.createdAt = timestamp;
        return runOnVertx(() -> Panache.withTransaction(event::persist));
    }

    private <T> T runOnVertx(Supplier<Uni<T>> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Context context = ((ContextInternal) vertx.getDelegate().getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            Context current = io.vertx.core.Vertx.currentContext();
            if (current != null) {
                VertxContextSafetyToggle.setContextSafe(current, true);
            }
            action.get().subscribe().with(future::complete, future::completeExceptionally);
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Reactive operation failed", e);
        }
    }
}
