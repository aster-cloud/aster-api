package io.aster.policy.service;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全审计 C1 回归：跨租户版本停用隔离。
 *
 * <p>{@code policyId = moduleName + "." + functionName} 并非租户唯一——两个租户可有同名
 * policyId。历史缺陷：激活/回滚经 {@code deactivateAllVersions(policyId)}（tenantless）会把
 * 其它租户同 policyId 的 active 版本一并停用（跨租户 DoS）。本测试锁定：租户 A 激活自己的版本，
 * 不得影响租户 B 同 policyId 的 active 版本。
 */
@QuarkusTest
class PolicyVersionCrossTenantTest {

    static final String MODULE = "aster.audit.crosstenant";
    static final String FUNCTION = "evaluate";
    static final String TENANT_A = "ct-tenant-a";
    static final String TENANT_B = "ct-tenant-b";

    @Inject
    PolicyVersionService versionService;

    private PolicyCatalog catalogA;
    private PolicyCatalog catalogB;

    @BeforeEach
    @Transactional
    void setUp() {
        cleanup();
        catalogA = persistCatalog(TENANT_A);
        catalogB = persistCatalog(TENANT_B);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        cleanup();
    }

    @Test
    void activatingTenantAVersionMustNotDeactivateTenantBActiveVersion() {
        // 租户 B：造一个 active=true 版本（同 policyId = MODULE.FUNCTION）。
        Long bVersionId = seedApprovedActiveVersion(catalogB, TENANT_B);
        assertTrue(PolicyVersion.<PolicyVersion>findById(bVersionId).active,
            "前置：租户 B 版本应 active");

        // 租户 A：创建 → 审批 → 激活自己的版本（同 policyId）。
        PolicyVersion aVersion = versionService.createVersion(catalogA.id, "// tenant A", "en-US");
        versionService.submitForApproval(aVersion.id, "a-drafter");
        versionService.approveVersion(aVersion.id, "a-approver");
        versionService.activateVersion(aVersion.id, "a-operator");

        // 断言：租户 A 版本 active；**租户 B 版本仍 active**（跨租户未被波及）。
        assertTrue(PolicyVersion.<PolicyVersion>findById(aVersion.id).active,
            "租户 A 版本应被激活");
        assertTrue(PolicyVersion.<PolicyVersion>findById(bVersionId).active,
            "C1 回归：租户 A 激活不得停用租户 B 同 policyId 的 active 版本（跨租户 DoS）");
    }

    @Test
    void deactivateAllVersionsTenantScopedOnlyTouchesOwnTenant() {
        Long aId = seedApprovedActiveVersion(catalogA, TENANT_A);
        Long bId = seedApprovedActiveVersion(catalogB, TENANT_B);
        String policyId = MODULE + "." + FUNCTION;

        long deactivated = deactivateScoped(policyId, TENANT_A);

        assertEquals(1, deactivated, "只应停用租户 A 的 1 个 active 版本");
        assertFalse(PolicyVersion.<PolicyVersion>findById(aId).active, "租户 A 版本应被停用");
        assertTrue(PolicyVersion.<PolicyVersion>findById(bId).active, "租户 B 版本不受影响");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @Transactional
    long deactivateScoped(String policyId, String tenantId) {
        return PolicyVersion.deactivateAllVersions(policyId, tenantId);
    }

    @Transactional
    Long seedApprovedActiveVersion(PolicyCatalog catalog, String tenantId) {
        PolicyVersion v = versionService.createVersion(catalog.id, "// seed " + tenantId, "en-US");
        v = PolicyVersion.findById(v.id);
        v.status = VersionStatus.APPROVED;
        v.active = true;
        v.persist();
        return v.id;
    }

    @Transactional
    void cleanup() {
        for (String t : new String[]{TENANT_A, TENANT_B}) {
            PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", t);
            io.aster.policy.entity.PolicyArtifact.delete(
                "policyVersionId in (select id from PolicyVersion where tenantId = ?1)", t);
            PolicyVersion.delete("tenantId = ?1", t);
            PolicyCatalog.delete("tenantId = ?1", t);
        }
    }

    @Transactional
    PolicyCatalog persistCatalog(String tenantId) {
        PolicyCatalog c = new PolicyCatalog();
        c.id = UUID.randomUUID();
        c.tenantId = tenantId;
        c.moduleName = MODULE;
        c.functionName = FUNCTION;
        c.domain = "audit";
        Instant now = Instant.now();
        c.createdAt = now;
        c.updatedAt = now;
        c.persist();
        return c;
    }
}
