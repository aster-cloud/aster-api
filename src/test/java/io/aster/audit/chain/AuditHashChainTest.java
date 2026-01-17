package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Task 3.2 - 审计哈希链功能测试
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class AuditHashChainTest {

    @Inject
    Pool pgPool;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @BeforeEach
    void cleanup() {
        pgPool.query("DELETE FROM audit_logs")
            .execute()
            .await().indefinitely();
    }

    @Test
    void testFirstRecordGenesisBlock() throws Exception {
        // 创建第一条审计记录（genesis block）
        String tenantId = "tenant-genesis";
        AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.policy", "evaluate");

        auditEventProducer.fireAsync(event);
        waitForAuditRecord(tenantId, 1);

        // 验证 genesis block
        AuditLog log = AuditLog.findByTenant(tenantId).get(0);
        assertNull(log.prevHash, "Genesis block should have prevHash = null");
        assertNotNull(log.currentHash, "Genesis block should have currentHash");
        assertEquals(64, log.currentHash.length(), "SHA256 hex should be 64 characters");
    }

    @Test
    void testSequentialRecords() throws Exception {
        String tenantId = "tenant-sequential";

        // 创建 3 条连续的审计记录
        for (int i = 1; i <= 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.policy", "eval" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i);
            Thread.sleep(50); // 确保时间戳不同
        }

        // 验证哈希链连续性
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertEquals(3, logs.size());

        // logs 按 timestamp desc 排序，需要反转
        AuditLog log1 = logs.get(2); // 最早
        AuditLog log2 = logs.get(1);
        AuditLog log3 = logs.get(0); // 最新

        // 验证第一条记录（genesis）
        assertNull(log1.prevHash);
        assertNotNull(log1.currentHash);

        // 验证第二条记录链接到第一条
        assertEquals(log1.currentHash, log2.prevHash, "log2.prevHash should equal log1.currentHash");
        assertNotNull(log2.currentHash);
        assertNotEquals(log1.currentHash, log2.currentHash, "Hashes should be different");

        // 验证第三条记录链接到第二条
        assertEquals(log2.currentHash, log3.prevHash, "log3.prevHash should equal log2.currentHash");
        assertNotNull(log3.currentHash);
        assertNotEquals(log2.currentHash, log3.currentHash, "Hashes should be different");
    }

    @Test
    void testHashComputation() throws Exception {
        String tenantId = "tenant-hash-test";
        Instant timestamp = Instant.parse("2025-01-15T10:00:00Z");

        AuditEvent event = new AuditEvent(
            EventType.POLICY_EVALUATION,
            timestamp,
            tenantId,
            "test.module",
            "testFunction",
            null,
            null,
            null,
            "test-user",
            true,
            100L,
            null,
            Map.of(),
            null, null, null, null  // Phase 3.7 fields
        );

        auditEventProducer.fireAsync(event);
        waitForAuditRecord(tenantId, 1);

        AuditLog log = AuditLog.findByTenant(tenantId).get(0);

        // 手动计算预期哈希值
        StringBuilder content = new StringBuilder();
        // prevHash is null for genesis
        content.append(log.eventType);
        content.append(log.timestamp.toString());
        content.append(log.tenantId);
        content.append(log.policyModule != null ? log.policyModule : "");
        content.append(log.policyFunction != null ? log.policyFunction : "");
        content.append(log.success.toString());

        String expectedHash = DigestUtils.sha256Hex(content.toString());

        assertEquals(expectedHash, log.currentHash, "Current hash should match computed hash");
    }

    @Test
    void testDifferentTenantsIsolated() throws Exception {
        // 为两个不同租户创建审计记录
        String tenantA = "tenant-A";
        String tenantB = "tenant-B";

        auditEventProducer.fireAsync(createEvent(tenantA, "POLICY_EVALUATION", "test.a", "eval1"));
        waitForAuditRecord(tenantA, 1);

        auditEventProducer.fireAsync(createEvent(tenantB, "POLICY_EVALUATION", "test.b", "eval1"));
        waitForAuditRecord(tenantB, 1);

        auditEventProducer.fireAsync(createEvent(tenantA, "POLICY_EVALUATION", "test.a", "eval2"));
        waitForAuditRecord(tenantA, 2);

        // 验证租户 A 的哈希链
        List<AuditLog> logsA = AuditLog.findByTenant(tenantA);
        assertEquals(2, logsA.size());
        AuditLog a1 = logsA.get(1); // 最早
        AuditLog a2 = logsA.get(0); // 最新
        assertNull(a1.prevHash);
        assertEquals(a1.currentHash, a2.prevHash);

        // 验证租户 B 的哈希链（独立）
        List<AuditLog> logsB = AuditLog.findByTenant(tenantB);
        assertEquals(1, logsB.size());
        AuditLog b1 = logsB.get(0);
        assertNull(b1.prevHash, "Tenant B should have its own genesis block");

        // 验证两个租户的哈希值不同
        assertNotEquals(a1.currentHash, b1.currentHash, "Different tenants should have different hashes");
    }

    @Test
    void testSequentialInsertsWithDelay() throws Exception {
        String tenantId = "tenant-sequential-delay";
        int count = 5;

        // 顺序创建多条审计记录，确保每条记录的事务都已提交
        for (int i = 0; i < count; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.sequential", "eval" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50); // 确保事务提交和时间戳不同
        }

        // 验证哈希链完整性
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertEquals(count, logs.size());

        // 反转列表以按时间升序排列
        List<AuditLog> sortedLogs = new ArrayList<>(logs);
        sortedLogs.sort((a, b) -> a.timestamp.compareTo(b.timestamp));

        // 验证哈希链连续性
        for (int i = 0; i < sortedLogs.size(); i++) {
            AuditLog log = sortedLogs.get(i);
            assertNotNull(log.currentHash, "All logs should have currentHash");

            if (i == 0) {
                assertNull(log.prevHash, "First log should have prevHash = null");
            } else {
                AuditLog prevLog = sortedLogs.get(i - 1);
                assertEquals(prevLog.currentHash, log.prevHash,
                    "Log " + i + " should link to previous log");
            }
        }
    }

    private AuditEvent createEvent(String tenantId, String eventType, String module, String function) {
        return new AuditEvent(
            EventType.valueOf(eventType),
            Instant.now(),
            tenantId,
            module,
            function,
            null,
            null,
            null,
            "test-user",
            true,
            50L,
            null,
            Map.of(),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    private void waitForAuditRecord(String tenantId, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) { // 最多等待 5 秒
            long count = AuditLog.count("tenantId = ?1", tenantId);
            if (count >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timeout waiting for audit record: tenant=" + tenantId + ", expected=" + expectedCount);
    }
}
