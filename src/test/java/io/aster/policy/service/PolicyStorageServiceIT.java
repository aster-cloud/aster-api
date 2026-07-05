package io.aster.policy.service;

import io.aster.policy.service.PolicyStorageService.PolicyDocument;
import io.aster.test.PostgresTestResource;
import io.aster.workflow.DeterminismContext;
import io.aster.api.workflow.PostgresWorkflowRuntime;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PolicyStorageService DB-backed 持久化验证。
 *
 * <p>验证 CRUD 真正落库（重启即丢的 GA blocker 已清除）、租户隔离、
 * 以及确定性 ID 在 workflow replay 下可重放（原 NonDeterminismSourceTest
 * 的 testPolicyStorageUuidReplay 迁移至此——createPolicy 现需真实数据源）。</p>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class PolicyStorageServiceIT {

    @Inject
    PolicyStorageService service;

    @InjectMock
    PostgresWorkflowRuntime workflowRuntime;

    @Test
    void createPersistsAndRoundTrips() {
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(null);

        PolicyDocument created = service.createPolicy(
            "tenant-crud",
            new PolicyDocument(null, "Loan Approval",
                Map.of("loan", List.of("amount <= 10000")),
                Map.of("loan", List.of("blacklist")),
                "Module aster.finance.loan."));

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getName()).isEqualTo("Loan Approval");

        // 重新读取——证明落库而非内存
        PolicyDocument fetched = service.getPolicy("tenant-crud", created.getId()).orElseThrow();
        assertThat(fetched.getName()).isEqualTo("Loan Approval");
        assertThat(fetched.getAllow()).containsKey("loan");
        assertThat(fetched.getDeny().get("loan")).containsExactly("blacklist");
        assertThat(fetched.getCnl()).isEqualTo("Module aster.finance.loan.");
    }

    @Test
    void updateThenListReflectsChange() {
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(null);

        PolicyDocument created = service.createPolicy(
            "tenant-update",
            new PolicyDocument(null, "Original", Map.of(), Map.of()));

        PolicyDocument updated = service.updatePolicy(
            "tenant-update", created.getId(),
            new PolicyDocument(null, "Renamed", Map.of("res", List.of("p")), Map.of()))
            .orElseThrow();

        assertThat(updated.getId()).isEqualTo(created.getId()); // ID 不变
        assertThat(updated.getName()).isEqualTo("Renamed");

        List<PolicyDocument> all = service.listPolicies("tenant-update");
        assertThat(all).anyMatch(d -> d.getId().equals(created.getId()) && d.getName().equals("Renamed"));
    }

    @Test
    void deleteRemovesAndReturnsFlag() {
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(null);

        PolicyDocument created = service.createPolicy(
            "tenant-delete",
            new PolicyDocument(null, "Doomed", Map.of(), Map.of()));

        assertThat(service.deletePolicy("tenant-delete", created.getId())).isTrue();
        assertThat(service.getPolicy("tenant-delete", created.getId())).isEmpty();
        // 二次删除返回 false
        assertThat(service.deletePolicy("tenant-delete", created.getId())).isFalse();
    }

    @Test
    void tenantIsolation() {
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(null);

        PolicyDocument a = service.createPolicy(
            "tenant-iso-a", new PolicyDocument(null, "A's policy", Map.of(), Map.of()));

        // 另一租户用同一 ID 查不到
        assertThat(service.getPolicy("tenant-iso-b", a.getId())).isEmpty();
        // 另一租户的列表不含 A 的策略
        assertThat(service.listPolicies("tenant-iso-b"))
            .noneMatch(d -> d.getId().equals(a.getId()));
        // 另一租户删不掉 A 的策略
        assertThat(service.deletePolicy("tenant-iso-b", a.getId())).isFalse();
        assertThat(service.getPolicy("tenant-iso-a", a.getId())).isPresent();
    }

    /**
     * 迁移自 NonDeterminismSourceTest：确定性 ID 在 workflow replay 下复用录制的 UUID。
     */
    @Test
    void deterministicIdReplaysRecordedUuid() {
        DeterminismContext recording = new DeterminismContext();
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(recording);

        PolicyDocument first = service.createPolicy(
            "tenant-replay",
            new PolicyDocument(null, "Policy A", Map.of(), Map.of()));
        String recordedId = first.getId();
        assertThat(recordedId).isNotBlank();

        // 模拟 workflow replay：重放同一次 createPolicy 会复用录制的 UUID。
        // DB 主键唯一，故先删除录制态产生的行，让重放态的 INSERT（同 ID）成功，
        // 从而验证「重放生成与录制相同的 ID」这一确定性契约。
        service.deletePolicy("tenant-replay", recordedId);

        DeterminismContext replay = new DeterminismContext();
        replay.uuid().enterReplayMode(recording.uuid().getRecordedUuids());
        Mockito.reset(workflowRuntime);
        Mockito.when(workflowRuntime.getDeterminismContext()).thenReturn(replay);

        PolicyDocument replayDoc = service.createPolicy(
            "tenant-replay",
            new PolicyDocument(null, "Policy B", Map.of(), Map.of()));

        // 重放下生成与录制相同的 ID
        assertThat(replayDoc.getId()).isEqualTo(recordedId);
    }
}
