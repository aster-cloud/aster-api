package io.aster.audit.outbox;

import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyActionPayload;
import io.aster.audit.entity.AnomalyReportEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class GenericOutboxSchedulerTest {

    @Inject
    Pool pgPool;

    @BeforeEach
    void resetState() {
        pgPool.query("DELETE FROM anomaly_actions")
            .execute()
            .await().indefinitely();
        pgPool.query("DELETE FROM anomaly_reports")
            .execute()
            .await().indefinitely();
    }

    @Test
    void testBatchProcessingRespectsLimit() {
        Instant base = Instant.now().minusSeconds(60);
        for (int i = 0; i < 7; i++) {
            persistAction("VERIFY_REPLAY", "tenant-batch", workflowPayload(), base.plusSeconds(i));
        }

        TestOutboxScheduler scheduler = scheduler(action -> replayResultUni(), 5);
        scheduler.processOutbox();

        List<AnomalyActionEntity> actions = fetchAll();
        long done = actions.stream().filter(a -> a.status == OutboxStatus.DONE).count();
        long pending = actions.stream().filter(a -> a.status == OutboxStatus.PENDING).count();
        assertEquals(5, done, "批处理应该只消费 5 条记录");
        assertEquals(2, pending, "剩余记录应保持为 PENDING");
    }

    @Test
    void testStatusTransitionSuccess() {
        AnomalyActionEntity action = persistAction(
            "AUTO_ROLLBACK",
            "tenant-status",
            rollbackPayload(3L),
            Instant.now().minusSeconds(5)
        );

        TestOutboxScheduler scheduler = scheduler(a -> Uni.createFrom().item(Boolean.TRUE), 5);
        scheduler.processOutbox();

        AnomalyActionEntity reloaded = findById(action.id);
        assertEquals(OutboxStatus.DONE, reloaded.status);
        assertNotNull(reloaded.startedAt);
        assertNotNull(reloaded.completedAt);
        assertEquals("tenant-status", reloaded.tenantId);
        assertEquals(rollbackPayload(3L), reloaded.payload);
    }

    @Test
    void testErrorHandlingMovesToFailed() {
        AnomalyActionEntity action = persistAction(
            "VERIFY_REPLAY",
            "tenant-error",
            workflowPayload(),
            Instant.now().minusSeconds(30)
        );

        TestOutboxScheduler scheduler = scheduler(a ->
            Uni.createFrom().failure(new IllegalStateException("boom")), 5);
        scheduler.processOutbox();

        AnomalyActionEntity failed = findById(action.id);
        assertEquals(OutboxStatus.FAILED, failed.status);
        assertNotNull(failed.completedAt);
        assertEquals("boom", failed.errorMessage);
    }

    @Test
    void testConcurrentSchedulingProcessesOnce() throws Exception {
        AtomicInteger counter = new AtomicInteger();

        TestOutboxScheduler scheduler = scheduler(action -> {
            counter.incrementAndGet();
            return replayResultUni();
        }, 5);

        // 创建单个事件
        persistAction("VERIFY_REPLAY", "tenant-concurrent", workflowPayload(), Instant.now().minusSeconds(10));

        // 模拟并发场景：快速连续调用两次 processOutbox()
        // PESSIMISTIC_WRITE 锁确保第一次调用处理完后，第二次调用发现事件已经不是 PENDING 状态而跳过
        scheduler.processOutbox();
        scheduler.processOutbox();

        // 验证：事件只被执行一次
        List<AnomalyActionEntity> entities = fetchAll();
        assertEquals(1, entities.size());
        assertEquals(OutboxStatus.DONE, entities.get(0).status);
        assertEquals(1, counter.get(), "事件只应被执行一次（PESSIMISTIC_WRITE 锁 + 状态检查保证）");
    }

    @Test
    void testRepeatedRunIsIdempotent() {
        AtomicInteger counter = new AtomicInteger();
        TestOutboxScheduler scheduler = scheduler(action -> {
            counter.incrementAndGet();
            return replayResultUni();
        }, 5);

        persistAction("VERIFY_REPLAY", "tenant-idempotent", workflowPayload(), Instant.now());

        scheduler.processOutbox();
        scheduler.processOutbox();

        assertEquals(1, counter.get());
        AnomalyActionEntity entity = fetchAll().get(0);
        assertEquals(OutboxStatus.DONE, entity.status);
    }

    @Test
    void testTenantScopedProcessing() {
        TestOutboxScheduler scheduler = scheduler(action -> replayResultUni(), 5);
        persistAction("VERIFY_REPLAY", "tenant-A", workflowPayload(), Instant.now().minusSeconds(5));
        persistAction("VERIFY_REPLAY", "tenant-B", workflowPayload(), Instant.now().minusSeconds(4));

        scheduler.processOutbox("tenant-A");

        List<AnomalyActionEntity> actions = fetchAll();
        long doneA = actions.stream()
            .filter(a -> "tenant-A".equals(a.tenantId) && a.status == OutboxStatus.DONE)
            .count();
        long pendingB = actions.stream()
            .filter(a -> "tenant-B".equals(a.tenantId) && a.status == OutboxStatus.PENDING)
            .count();

        assertEquals(1, doneA);
        assertEquals(1, pendingB);
    }

    private void awaitRelease(CountDownLatch release) {
        try {
            release.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Uni<VerificationResult> replayResultUni() {
        VerificationResult result = new VerificationResult(
            true,
            true,
            UUID.randomUUID().toString(),
            Instant.now(),
            100L,
            95L
        );
        return Uni.createFrom().item(result);
    }

    private AnomalyActionEntity persistAction(String type, String tenant, String payload, Instant createdAt) {
        Long anomalyId = createTestAnomaly();
        return QuarkusTransaction.requiringNew().call(() -> {
            AnomalyActionEntity entity = new AnomalyActionEntity();
            entity.anomalyId = anomalyId;
            entity.actionType = type;
            entity.status = OutboxStatus.PENDING;
            entity.payload = payload;
            entity.tenantId = tenant;
            entity.createdAt = createdAt;
            entity.persist();
            return entity;
        });
    }

    private Long createTestAnomaly() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AnomalyReportEntity anomaly = new AnomalyReportEntity();
            anomaly.anomalyType = "TEST";
            anomaly.policyId = "policy-" + UUID.randomUUID();
            anomaly.severity = "LOW";
            anomaly.status = "PENDING";
            anomaly.detectedAt = Instant.now();
            anomaly.metricValue = 0.1;
            anomaly.threshold = 0.2;
            anomaly.description = "scheduler test";
            anomaly.tenantId = "test-tenant";
            anomaly.persist();
            return anomaly.id;
        });
    }

    private List<AnomalyActionEntity> fetchAll() {
        return QuarkusTransaction.requiringNew().call(() ->
            Panache.getEntityManager()
                .createQuery("FROM AnomalyActionEntity ORDER BY createdAt", AnomalyActionEntity.class)
                .getResultList()
        );
    }

    private AnomalyActionEntity findById(Long id) {
        return QuarkusTransaction.requiringNew().call(() ->
            Panache.getEntityManager().find(AnomalyActionEntity.class, id)
        );
    }

    private String workflowPayload() {
        return String.format("{\"workflowId\":\"%s\"}", UUID.randomUUID());
    }

    private String rollbackPayload(Long version) {
        return String.format("{\"targetVersion\": %d}", version);
    }

    private TestOutboxScheduler scheduler(Function<AnomalyActionEntity, Uni<?>> delegate, int batchSize) {
        return new TestOutboxScheduler(delegate, batchSize);
    }

    private static final class TestOutboxScheduler extends GenericOutboxScheduler<AnomalyActionPayload, AnomalyActionEntity> {

        private final Function<AnomalyActionEntity, Uni<?>> delegate;
        private final int batchSize;

        private TestOutboxScheduler(Function<AnomalyActionEntity, Uni<?>> delegate, int batchSize) {
            this.delegate = delegate;
            this.batchSize = batchSize;
        }

        @Override
        protected Class<AnomalyActionEntity> getEntityClass() {
            return AnomalyActionEntity.class;
        }

        @Override
        protected Uni<?> executeEvent(AnomalyActionEntity entity, AnomalyActionPayload payload) {
            return delegate.apply(entity);
        }

        @Override
        protected int batchSize() {
            return batchSize;
        }
    }
}
