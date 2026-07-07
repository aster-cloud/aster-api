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
import io.aster.test.BlockingDbTestHelper;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    BlockingDbTestHelper db;

    @BeforeEach
    void resetState() {
        db.execute("DELETE FROM anomaly_actions");
        db.execute("DELETE FROM anomaly_reports");
    }

    @Test
    void testBatchProcessingRespectsLimit() {
        Instant base = Instant.now().minusSeconds(60);
        for (int i = 0; i < 7; i++) {
            persistAction("VERIFY_REPLAY", "tenant-batch", workflowPayload(), base.plusSeconds(i));
        }

        TestOutboxScheduler scheduler = scheduler(action -> replayResultUni(), 5);
        // 按本测试租户处理，避免依赖"全表只有本测试数据"（fast 套件共享物理 anomaly_actions
        // 表，别的测试并存的行会被无参 processOutbox 捞入 limit、被全表 fetchAll 数入断言 → flaky）。
        scheduler.processOutbox("tenant-batch");

        List<AnomalyActionEntity> actions = fetchAll("tenant-batch");
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
        scheduler.processOutbox("tenant-concurrent");
        scheduler.processOutbox("tenant-concurrent");

        // 验证：事件只被执行一次
        List<AnomalyActionEntity> entities = fetchAll("tenant-concurrent");
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

        scheduler.processOutbox("tenant-idempotent");
        scheduler.processOutbox("tenant-idempotent");

        assertEquals(1, counter.get());
        AnomalyActionEntity entity = fetchAll("tenant-idempotent").get(0);
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

    // ==================== issue #119：长事务拆分 + lease token + reclaim ====================

    @Test
    void testLeaseTokenSetDuringRunAndClearedOnDone() throws Exception {
        // 拆分后：claim 事务写入 lease token，handler 执行中该行应为 RUNNING 且 token 非空；
        // finalize 后 token 被清空。用 handler 内快照捕获执行中状态。
        AnomalyActionEntity action = persistAction(
            "VERIFY_REPLAY", "tenant-lease", workflowPayload(), Instant.now().minusSeconds(5));

        String[] tokenDuringRun = new String[1];
        OutboxStatus[] statusDuringRun = new OutboxStatus[1];
        TestOutboxScheduler scheduler = scheduler(a -> {
            // handler 在无外层 outbox 事务下运行；此刻另开事务读该行的持久化状态。
            AnomalyActionEntity mid = findById(action.id);
            statusDuringRun[0] = mid.status;
            tokenDuringRun[0] = mid.leaseToken;
            return replayResultUni();
        }, 5);

        scheduler.processOutbox("tenant-lease");

        assertEquals(OutboxStatus.RUNNING, statusDuringRun[0], "handler 执行期间该行应为 RUNNING");
        assertNotNull(tokenDuringRun[0], "claim 应写入 lease token");

        AnomalyActionEntity done = findById(action.id);
        assertEquals(OutboxStatus.DONE, done.status);
        assertNull(done.leaseToken, "finalize 后应清空 lease token");
    }

    @Test
    void testStaleRunningIsReclaimedAndReprocessed() {
        // 模拟崩溃遗留：一条 RUNNING、startedAt 远早于 reclaimTimeout、带旧 token。
        // 下一轮 processOutbox 应先回收为 PENDING（清 token），再重新领取并执行完成。
        Long anomalyId = createTestAnomaly();
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            AnomalyActionEntity e = new AnomalyActionEntity();
            e.anomalyId = anomalyId;
            e.actionType = "VERIFY_REPLAY";
            e.status = OutboxStatus.RUNNING;                     // 卡死的 RUNNING
            e.payload = workflowPayload();
            e.tenantId = "tenant-reclaim";
            e.createdAt = Instant.now().minusSeconds(1200);
            e.startedAt = Instant.now().minus(Duration.ofMinutes(10)); // 远超 5min reclaimTimeout
            e.leaseToken = "STALE-TOKEN";
            e.persist();
            return e.id;
        });

        AtomicInteger runs = new AtomicInteger();
        TestOutboxScheduler scheduler = scheduler(a -> {
            runs.incrementAndGet();
            return replayResultUni();
        }, 5);
        scheduler.processOutbox("tenant-reclaim");

        AnomalyActionEntity reloaded = findById(id);
        assertEquals(OutboxStatus.DONE, reloaded.status, "stale RUNNING 应被回收→重投递→完成");
        assertEquals(1, runs.get(), "回收后应重新执行一次 handler");
        assertNull(reloaded.leaseToken, "完成后 token 清空");
    }

    @Test
    void testFreshRunningIsNotReclaimed() {
        // 一条 RUNNING、startedAt 很近（模拟另一 pod 正在正常执行）→ 不应被回收，handler 不应被触发。
        Long anomalyId = createTestAnomaly();
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            AnomalyActionEntity e = new AnomalyActionEntity();
            e.anomalyId = anomalyId;
            e.actionType = "VERIFY_REPLAY";
            e.status = OutboxStatus.RUNNING;
            e.payload = workflowPayload();
            e.tenantId = "tenant-fresh-running";
            e.createdAt = Instant.now().minusSeconds(10);
            e.startedAt = Instant.now().minusSeconds(5);       // 远未过期
            e.leaseToken = "ACTIVE-TOKEN";
            e.persist();
            return e.id;
        });

        AtomicInteger runs = new AtomicInteger();
        TestOutboxScheduler scheduler = scheduler(a -> {
            runs.incrementAndGet();
            return replayResultUni();
        }, 5);
        scheduler.processOutbox("tenant-fresh-running");

        AnomalyActionEntity reloaded = findById(id);
        assertEquals(OutboxStatus.RUNNING, reloaded.status, "未过期 RUNNING 不应被回收");
        assertEquals("ACTIVE-TOKEN", reloaded.leaseToken, "未过期 RUNNING 的 token 不应变");
        assertEquals(0, runs.get(), "未过期 RUNNING 不应被重新执行");
    }

    @Test
    void testLateFinalizeDoesNotClobberReclaimedReattempt() throws Exception {
        // ABA 核心场景：旧 attempt 的 handler 执行期间，该行被回收并被新 attempt 重新领取（token 变），
        // 旧 attempt 迟到的 finalize 必须因 token 失配而放弃，不得覆盖新 attempt 的状态。
        // 用 handler 内“把该行改成另一 attempt 的 RUNNING+新 token”模拟被抢占，然后旧 attempt 成功返回。
        AnomalyActionEntity action = persistAction(
            "VERIFY_REPLAY", "tenant-aba", workflowPayload(), Instant.now().minusSeconds(5));

        TestOutboxScheduler scheduler = scheduler(a -> {
            // 模拟：回收线程 + 新 attempt 已介入，把行改成新 token 的 RUNNING（新 attempt 尚未完成）。
            QuarkusTransaction.requiringNew().run(() -> {
                AnomalyActionEntity row = Panache.getEntityManager()
                    .find(AnomalyActionEntity.class, action.id);
                row.leaseToken = "NEW-ATTEMPT-TOKEN";
                row.startedAt = Instant.now();
                row.persist();
            });
            return replayResultUni(); // 旧 attempt 成功，随后尝试 finalize（应被 token 守卫拦下）
        }, 5);

        scheduler.processOutbox("tenant-aba");

        AnomalyActionEntity reloaded = findById(action.id);
        assertEquals(OutboxStatus.RUNNING, reloaded.status,
            "旧 attempt 的迟到 finalize 不得把新 attempt 的 RUNNING 覆盖为 DONE");
        assertEquals("NEW-ATTEMPT-TOKEN", reloaded.leaseToken,
            "新 attempt 的 token 应保持不变（旧 attempt 未清空它）");
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

    /**
     * 按租户过滤——避免依赖"全表只有本测试数据"。fast 套件共享物理 anomaly_actions 表，
     * 全表查询会数入其它测试并存的行导致断言漂移 flaky；各测试用唯一租户 + 本方法过滤天然隔离。
     */
    private List<AnomalyActionEntity> fetchAll(String tenantId) {
        return QuarkusTransaction.requiringNew().call(() ->
            Panache.getEntityManager()
                .createQuery("FROM AnomalyActionEntity WHERE tenantId = :tenant ORDER BY createdAt",
                    AnomalyActionEntity.class)
                .setParameter("tenant", tenantId)
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
