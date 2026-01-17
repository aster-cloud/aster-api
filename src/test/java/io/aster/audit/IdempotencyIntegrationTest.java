package io.aster.audit;

import io.aster.audit.entity.AnomalyActionEntity;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.inbox.InboxEvent;
import io.aster.audit.rest.model.VerificationResult;
import io.aster.audit.service.AnomalyActionExecutor;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.service.PolicyVersionService;
import io.aster.test.PostgresTestResource;
import io.aster.workflow.WorkflowEventEntity;
import io.aster.workflow.WorkflowStateEntity;
import io.aster.workflow.WorkflowTimerEntity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;

/**
 * Phase 0 Task 2.3 - 幂等性集成测试
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
public class IdempotencyIntegrationTest {

    private static final String DEFAULT_TENANT = "idempotency-tenant";
    private static final String KEY_PREFIX = "IDEMP-";
    private static final String POLICY_PREFIX = "idempotency-policy-";

    @Inject
    PolicyVersionService policyVersionService;

    @Inject
    AnomalyActionExecutor actionExecutor;

    @Inject
    EntityManager entityManager;

    private final List<Long> anomalyIdsToClean = new ArrayList<>();
    private final List<String> policyIdsToClean = new ArrayList<>();
    private final List<UUID> workflowIdsToClean = new ArrayList<>();

    @BeforeEach
    @Transactional
    void resetInbox() {
        deleteAllInboxRecords();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        if (!anomalyIdsToClean.isEmpty()) {
            AnomalyReportEntity.delete("id in ?1", anomalyIdsToClean);
            anomalyIdsToClean.clear();
        }
        if (!policyIdsToClean.isEmpty()) {
            for (String policyId : policyIdsToClean) {
                PolicyVersion.delete("policyId = ?1", policyId);
            }
            policyIdsToClean.clear();
        }
        if (!workflowIdsToClean.isEmpty()) {
            for (UUID workflowId : workflowIdsToClean) {
                WorkflowEventEntity.delete("workflowId = ?1", workflowId);
                WorkflowTimerEntity.delete("workflowId = ?1", workflowId);
                WorkflowStateEntity.delete("workflowId = ?1", workflowId);
            }
            workflowIdsToClean.clear();
        }
        deleteAllInboxRecords();
    }

    @Test
    void testUpdateAnomalyStatusIdempotency() {
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "status-1", null, "PENDING");
        String key = newKey();

        sendStatusUpdate(anomalyId, key, DEFAULT_TENANT, "DISMISSED")
            .then()
            .statusCode(204);

        Assertions.assertEquals("DISMISSED", fetchAnomalyStatus(anomalyId));
        Assertions.assertTrue(inboxExists(normalizeKey(DEFAULT_TENANT, key)));
    }

    @Test
    void testUpdateAnomalyStatusDuplicate() {
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "status-dup", null, "PENDING");
        String key = newKey();

        sendStatusUpdate(anomalyId, key, DEFAULT_TENANT, "DISMISSED")
            .then()
            .statusCode(204);

        Response duplicate = sendStatusUpdate(anomalyId, key, DEFAULT_TENANT, "DISMISSED");
        duplicate.then()
            .statusCode(409)
            .body("error", Matchers.equalTo("Duplicate request"))
            .body("idempotencyKey", Matchers.equalTo(key));
        Assertions.assertEquals("DISMISSED", fetchAnomalyStatus(anomalyId));
    }

    @Test
    void testCreatePolicyIdempotency() {
        String tenant = DEFAULT_TENANT + "-graphql";
        String key = newKey();
        String policyName = "Policy-" + UUID.randomUUID();

        executeGraphQL(tenant, key, createPolicyMutation(policyName))
            .then()
            .statusCode(200)
            .body("errors", Matchers.nullValue())
            .body("data.createPolicy.name", Matchers.equalTo(policyName));

        Assertions.assertTrue(inboxExists(normalizeKey(tenant, key)));
    }

    @Test
    void testCreatePolicyDuplicate() {
        String tenant = DEFAULT_TENANT + "-graphql";
        String key = newKey();
        String policyName = "Policy-" + UUID.randomUUID();
        String mutation = createPolicyMutation(policyName);

        executeGraphQL(tenant, key, mutation)
            .then()
            .statusCode(200)
            .body("errors", Matchers.nullValue());

        Response duplicate = executeGraphQL(tenant, key, mutation);
        duplicate.then()
            .statusCode(200)
            .body("data.createPolicy", Matchers.nullValue())
            .body("errors[0].message", Matchers.containsString("Duplicate request"));
    }

    @Test
    void testReplayVerificationIdempotency() {
        UUID workflowId = UUID.randomUUID();
        persistWorkflowState(workflowId, null);
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "replay", null, "VERIFYING");

        AnomalyActionEntity action = new AnomalyActionEntity();
        action.id = 10L;
        action.anomalyId = anomalyId;
        action.actionType = "VERIFY_REPLAY";
        action.payload = "{\"workflowId\":\"" + workflowId + "\"}";

        VerificationResult first = actionExecutor.executeReplayVerification(action)
            .await().indefinitely();
        Assertions.assertNotNull(first);
        Assertions.assertEquals(workflowId.toString(), first.workflowId());

        VerificationResult duplicate = actionExecutor.executeReplayVerification(action)
            .await().indefinitely();
        Assertions.assertNull(duplicate);

        Assertions.assertTrue(inboxExists(normalizeKey(internalTenant(anomalyId), replayKey(anomalyId, action.id))));
    }

    @Test
    void testAutoRollbackIdempotency() {
        String policyId = POLICY_PREFIX + "rollback" + UUID.randomUUID();
        PolicyVersion version = policyVersionService.createVersion(
            policyId,
            "aster.module",
            "fn",
            "function fn() { return true; }",
            "tester",
            "rollback"
        );
        policyIdsToClean.add(policyId);

        Long anomalyId = createTestAnomaly(policyId, version.id, "PENDING");

        AnomalyActionEntity action = new AnomalyActionEntity();
        action.id = 20L;
        action.anomalyId = anomalyId;
        action.actionType = "AUTO_ROLLBACK";
        action.payload = "{\"targetVersion\":" + version.version + "}";

        Boolean first = actionExecutor.executeAutoRollback(action).await().indefinitely();
        Assertions.assertTrue(first);

        Boolean second = actionExecutor.executeAutoRollback(action).await().indefinitely();
        Assertions.assertTrue(second);

        Assertions.assertTrue(inboxExists(normalizeKey(internalTenant(anomalyId), rollbackKey(anomalyId, action.id))));
    }

    @Test
    void testConcurrentRequests() {
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "concurrent", null, "PENDING");
        String key = newKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            return sendStatusUpdate(anomalyId, key, DEFAULT_TENANT, "DISMISSED").getStatusCode();
        };

        Future<Integer> first = executor.submit(task);
        Future<Integer> second = executor.submit(task);
        start.countDown();

        int statusA;
        int statusB;
        try {
            statusA = first.get(10, TimeUnit.SECONDS);
            statusB = second.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("并发请求执行失败", e);
        } finally {
            executor.shutdownNow();
        }

        Assertions.assertTrue((statusA == 204 && statusB == 409) || (statusA == 409 && statusB == 204));
    }

    @Test
    void testDifferentTenantsIsolation() {
        Long anomalyA = createTestAnomaly(POLICY_PREFIX + "tenantA", null, "PENDING", "tenant-A");
        Long anomalyB = createTestAnomaly(POLICY_PREFIX + "tenantB", null, "PENDING", "tenant-B");
        String key = newKey();

        sendStatusUpdate(anomalyA, key, "tenant-A", "DISMISSED")
            .then().statusCode(204);
        sendStatusUpdate(anomalyB, key, "tenant-B", "DISMISSED")
            .then().statusCode(204);

        Assertions.assertTrue(inboxExists(normalizeKey("tenant-A", key)));
        Assertions.assertTrue(inboxExists(normalizeKey("tenant-B", key)));
    }

    @Test
    void testBackwardCompatibilityNoHeader() {
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "no-header", null, "PENDING");
        sendStatusUpdate(anomalyId, null, DEFAULT_TENANT, "DISMISSED")
            .then().statusCode(204);
        Assertions.assertEquals(0L, inboxCount());
    }

    @Test
    void testBackwardCompatibilityBlankHeader() {
        Long anomalyId = createTestAnomaly(POLICY_PREFIX + "blank-header", null, "PENDING");
        sendStatusUpdate(anomalyId, "   ", DEFAULT_TENANT, "DISMISSED")
            .then().statusCode(204);
        Assertions.assertEquals(0L, inboxCount());
    }

    private Response sendStatusUpdate(Long anomalyId, String key, String tenant, String status) {
        var request = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "status", status,
                "notes", "idempotency-test"
            ));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        return request
            .when()
            .patch("/api/audit/anomalies/" + anomalyId + "/status")
            .andReturn();
    }

    private Response executeGraphQL(String tenant, String key, String query) {
        var request = given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query));
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request.when().post("/graphql").andReturn();
    }

    private String createPolicyMutation(String name) {
        return """
            mutation {
              createPolicy(input: {
                name: \"%s\"
                allow: { rules: [{ resourceType: \"loan\", patterns: [\"READ\"] }] }
                deny: { rules: [] }
              }) {
                id
                name
              }
            }
            """.formatted(name);
    }

    @Transactional
    Long createTestAnomaly(String policyId, Long versionId, String status) {
        return createTestAnomaly(policyId, versionId, status, DEFAULT_TENANT);
    }

    @Transactional
    Long createTestAnomaly(String policyId, Long versionId, String status, String tenantId) {
        AnomalyReportEntity entity = new AnomalyReportEntity();
        entity.anomalyType = "HIGH_FAILURE_RATE";
        entity.versionId = versionId;
        entity.policyId = policyId;
        entity.metricValue = 0.6;
        entity.threshold = 0.3;
        entity.severity = "CRITICAL";
        entity.description = "Idempotency test anomaly";
        entity.recommendation = "Investigate";
        entity.detectedAt = Instant.now();
        entity.status = status;
        entity.tenantId = tenantId;
        entity.persist();
        anomalyIdsToClean.add(entity.id);
        return entity.id;
    }

    @Transactional
    String fetchAnomalyStatus(Long anomalyId) {
        AnomalyReportEntity entity = AnomalyReportEntity.findById(anomalyId);
        return entity != null ? entity.status : null;
    }

    @Transactional
    boolean inboxExists(String key) {
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM inbox_events WHERE idempotency_key = :key"
            )
            .setParameter("key", key)
            .getSingleResult();
        return count != null && count.longValue() > 0;
    }

    @Transactional
    long inboxCount() {
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM inbox_events")
            .getSingleResult();
        return count != null ? count.longValue() : 0L;
    }

    @Transactional
    void persistWorkflowState(UUID workflowId, String clockTimes) {
        WorkflowStateEntity state = new WorkflowStateEntity();
        state.workflowId = workflowId;
        state.status = "FAILED";
        state.durationMs = 150L;
        state.errorMessage = "test";
        state.clockTimes = clockTimes;
        state.persist();
        workflowIdsToClean.add(workflowId);
    }

    private String newKey() {
        return KEY_PREFIX + UUID.randomUUID();
    }

    private String normalizeKey(String tenant, String key) {
        if (tenant == null || tenant.isBlank()) {
            return key;
        }
        return tenant + ":" + key;
    }

    private String internalTenant(Long anomalyId) {
        return "ANOMALY-" + anomalyId;
    }

    private String replayKey(Long anomalyId, Long actionId) {
        return "REPLAY_" + anomalyId + "_" + actionId;
    }

    private String rollbackKey(Long anomalyId, Long actionId) {
        return "ROLLBACK_" + anomalyId + "_" + actionId;
    }

    private void deleteAllInboxRecords() {
        entityManager.createNativeQuery("DELETE FROM inbox_events").executeUpdate();
    }
}
