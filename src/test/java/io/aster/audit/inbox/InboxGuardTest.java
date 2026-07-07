package io.aster.audit.inbox;

import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InboxGuard 幂等性保护服务测试
 * 验证核心 CAS 行为与清理任务（#57 后已统一为 blocking 实现）。
 *
 * <p>{@code tryAcquire} 仍暴露 {@code Uni} 接口，但底层是 blocking JDBC 经 worker pool
 * offload——测试直接 {@code .await()} 即可（不再需要 Vert.x 上下文脚手架）。
 * {@code tryAcquireBlocking}/{@code scheduledCleanup} 是同步方法，直接调用。
 * 测试 DB 的 setup/cleanup 用 blocking {@link BlockingDbTestHelper}（#57 后测试也去 reactive）。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class InboxGuardTest {

    @Inject
    InboxGuard inboxGuard;

    @Inject
    BlockingDbTestHelper db;

    @BeforeEach
    void cleanInbox() {
        db.execute("DELETE FROM inbox_events");
    }

    @Test
    void testTryAcquireSuccess() {
        Boolean acquired = inboxGuard.tryAcquire("test-key-success", "TEST_EVENT", "tenant-1")
            .await().atMost(Duration.ofSeconds(10));
        assertThat(acquired).isTrue();
    }

    @Test
    void testTryAcquireDuplicate() {
        String key = "test-key-duplicate";
        String tenant = "tenant-duplicate";

        Boolean first = inboxGuard.tryAcquire(key, "TEST_EVENT", tenant)
            .await().atMost(Duration.ofSeconds(10));
        Boolean second = inboxGuard.tryAcquire(key, "TEST_EVENT", tenant)
            .await().atMost(Duration.ofSeconds(10));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void testTryAcquireBlockingDuplicate() {
        // 直接测 blocking 入口（@Blocking REST / @Scheduled outbox 走这个）。
        String key = "test-key-blocking";
        String tenant = "tenant-blocking";
        assertThat(inboxGuard.tryAcquireBlocking(key, "TEST_EVENT", tenant)).isTrue();
        assertThat(inboxGuard.tryAcquireBlocking(key, "TEST_EVENT", tenant)).isFalse();
    }

    @Test
    void testDifferentTenantsSeparate() {
        String key = "test-key-tenant-scope";

        Boolean tenantOne = inboxGuard.tryAcquire(key, "TEST_EVENT", "tenant-A")
            .await().atMost(Duration.ofSeconds(10));
        Boolean tenantTwo = inboxGuard.tryAcquire(key, "TEST_EVENT", "tenant-B")
            .await().atMost(Duration.ofSeconds(10));

        assertThat(tenantOne).isTrue();
        assertThat(tenantTwo).isTrue();
    }

    @Test
    void testNullKeyHandling() {
        Boolean nullKey = inboxGuard.tryAcquire(null, "TEST_EVENT", "tenant-null")
            .await().atMost(Duration.ofSeconds(10));
        Boolean blankKey = inboxGuard.tryAcquire("   ", "TEST_EVENT", "tenant-null")
            .await().atMost(Duration.ofSeconds(10));

        assertThat(nullKey).isFalse();
        assertThat(blankKey).isFalse();
    }

    @Test
    void testScheduledCleanup() {
        persistCustomEvent("cleanup-old", Instant.now().minus(30, ChronoUnit.DAYS));
        persistCustomEvent("cleanup-new", Instant.now().minus(1, ChronoUnit.DAYS));

        // scheduledCleanup 现为 blocking void，直接调用（@Transactional 生效）。
        inboxGuard.scheduledCleanup();

        assertThat(inboxRecordExists("cleanup-old")).isFalse();
        assertThat(inboxRecordExists("cleanup-new")).isTrue();
    }

    private void persistCustomEvent(String key, Instant processedAt) {
        // processed_at 是无时区 TIMESTAMP，存 UTC 挂钟（与 scheduledCleanup 的 DB CURRENT_TIMESTAMP
        // 及 tryAcquireBlocking 插入语义一致）。用 UTC LocalDateTime→Timestamp.valueOf 绑定。
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf(
            LocalDateTime.ofInstant(processedAt, ZoneOffset.UTC));
        db.execute(
            "INSERT INTO inbox_events (idempotency_key, event_type, tenant_id, processed_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?)",
            key, "TEST_EVENT", "tenant-cleanup", ts, ts);
    }

    private boolean inboxRecordExists(String key) {
        return db.queryLong("SELECT COUNT(*) AS cnt FROM inbox_events WHERE idempotency_key = ?", key) > 0;
    }
}
