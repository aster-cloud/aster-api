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
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InboxGuard 幂等性保护服务测试
 * 验证核心 CAS 行为与清理任务
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class InboxGuardTest {

    @Inject
    InboxGuard inboxGuard;

    @Inject
    Vertx vertx;

    @Inject
    Pool pgPool;

    @BeforeEach
    void cleanInbox() {
        runOnVertx(() ->
            pgPool.query("DELETE FROM inbox_events")
                .execute()
                .replaceWith((Void) null)
        );
    }

    @Test
    void testTryAcquireSuccess() {
        Boolean acquired = runOnVertx(() ->
            inboxGuard.tryAcquire("test-key-success", "TEST_EVENT", "tenant-1")
        );

        assertThat(acquired).isTrue();
    }

    @Test
    void testTryAcquireDuplicate() {
        String key = "test-key-duplicate";
        String tenant = "tenant-duplicate";

        Boolean first = runOnVertx(() ->
            inboxGuard.tryAcquire(key, "TEST_EVENT", tenant)
        );
        Boolean second = runOnVertx(() ->
            inboxGuard.tryAcquire(key, "TEST_EVENT", tenant)
        );

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void testDifferentTenantsSeparate() {
        String key = "test-key-tenant-scope";

        Boolean tenantOne = runOnVertx(() ->
            inboxGuard.tryAcquire(key, "TEST_EVENT", "tenant-A")
        );
        Boolean tenantTwo = runOnVertx(() ->
            inboxGuard.tryAcquire(key, "TEST_EVENT", "tenant-B")
        );

        assertThat(tenantOne).isTrue();
        assertThat(tenantTwo).isTrue();
    }

    @Test
    void testNullKeyHandling() {
        Boolean nullKey = runOnVertx(() ->
            inboxGuard.tryAcquire(null, "TEST_EVENT", "tenant-null")
        );
        Boolean blankKey = runOnVertx(() ->
            inboxGuard.tryAcquire("   ", "TEST_EVENT", "tenant-null")
        );

        assertThat(nullKey).isFalse();
        assertThat(blankKey).isFalse();
    }

    @Test
    void testScheduledCleanup() {
        persistCustomEvent("cleanup-old", Instant.now().minus(30, ChronoUnit.DAYS));
        persistCustomEvent("cleanup-new", Instant.now().minus(1, ChronoUnit.DAYS));

        runOnVertx(inboxGuard::scheduledCleanup);

        boolean oldExists = inboxRecordExists("cleanup-old");
        boolean newExists = inboxRecordExists("cleanup-new");

        assertThat(oldExists).isFalse();
        assertThat(newExists).isTrue();
    }

    private void persistCustomEvent(String key, Instant processedAt) {
        LocalDateTime timestamp = LocalDateTime.ofInstant(processedAt, ZoneOffset.UTC);

        runOnVertx(() ->
            pgPool.preparedQuery("INSERT INTO inbox_events (idempotency_key, event_type, tenant_id, processed_at, created_at) VALUES ($1, $2, $3, $4, $5)")
                .execute(Tuple.of(
                    key,
                    "TEST_EVENT",
                    "tenant-cleanup",
                    timestamp,
                    timestamp
                ))
                .replaceWith((Void) null)
        );
    }

    private boolean inboxRecordExists(String key) {
        return runOnVertx(() ->
            pgPool.preparedQuery("SELECT COUNT(*) AS cnt FROM inbox_events WHERE idempotency_key = $1")
                .execute(Tuple.of(key))
                .map(rows -> {
                    java.util.Iterator<io.vertx.mutiny.sqlclient.Row> iterator = rows.iterator();
                    return iterator.hasNext() && iterator.next().getLong("cnt") > 0;
                })
        );
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
