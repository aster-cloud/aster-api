package io.aster.policy.repository;

import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PolicySourceRepository 集成测试
 *
 * 目的：验证仓库在 PostgreSQL/Panache 环境下的查询逻辑，确保多种边界条件均被处理。
 */
@QuarkusTest
class PolicySourceRepositoryTest {

    @Inject
    PolicySourceRepository repository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @Transactional
    void cleanup() {
        // 仅清理测试所插入的数据，避免破坏 Flyway 的演示数据
        entityManager.createNativeQuery(
                "DELETE FROM policy_artifacts " +
                    "WHERE policy_version_id IN (" +
                    "   SELECT id FROM policy_versions WHERE policy_id LIKE :policyPrefix" +
                    ")"
            )
            .setParameter("policyPrefix", "repo-%")
            .executeUpdate();

        entityManager.createNativeQuery(
                "DELETE FROM policy_versions WHERE policy_id LIKE :policyPrefix"
            )
            .setParameter("policyPrefix", "repo-%")
            .executeUpdate();

        entityManager.createNativeQuery(
                "DELETE FROM policy_catalog WHERE tenant_id LIKE :tenantPrefix"
            )
            .setParameter("tenantPrefix", "repo-%")
            .executeUpdate();
    }

    @Test
    @Transactional
    void findActiveCatalogShouldReturnMatch() {
        PolicyCatalog catalog = persistCatalog(
            UUID.randomUUID(),
            "repo-tenant-a",
            "aster.module",
            "evaluate",
            null
        );

        Optional<PolicyCatalog> found = repository.findActiveCatalog("repo-tenant-a", "aster.module", "evaluate");
        assertTrue(found.isPresent(), "应该能匹配到活跃目录");
        assertEquals(catalog.id, found.get().id);

        assertTrue(repository.findActiveCatalog("repo-tenant-a", "aster.module", "missing").isEmpty(), "函数不匹配时应返回空");
        assertTrue(repository.findActiveCatalog(null, "aster.module", "evaluate").isEmpty(), "空参数需要返回空结果");
    }

    @Test
    @Transactional
    void findActiveVersionShouldResolveDefaultVersion() {
        PolicyVersion activeVersion = persistVersion("repo-policy-active", true);
        PolicyCatalog catalog = persistCatalog(
            UUID.randomUUID(),
            "repo-tenant-b",
            "aster.module",
            "evaluate",
            activeVersion.id
        );

        Optional<PolicyVersion> resolved = repository.findActiveVersion(catalog.id);
        assertTrue(resolved.isPresent(), "应找到默认活跃版本");
        assertEquals(activeVersion.id, resolved.get().id);

        // 无默认版本的目录
        PolicyCatalog withoutDefault = persistCatalog(
            UUID.randomUUID(),
            "repo-tenant-b",
            "module-2",
            "fn-2",
            null
        );
        assertTrue(repository.findActiveVersion(withoutDefault.id).isEmpty(), "未配置默认版本时应返回空");

        // 默认版本已被停用
        PolicyVersion inactive = persistVersion("repo-policy-inactive", false);
        PolicyCatalog inactiveCatalog = persistCatalog(
            UUID.randomUUID(),
            "repo-tenant-b",
            "module-3",
            "fn-3",
            inactive.id
        );
        assertTrue(repository.findActiveVersion(inactiveCatalog.id).isEmpty(), "非活跃版本无法返回");

        assertTrue(repository.findActiveVersion(null).isEmpty(), "空目录ID需要返回空");
    }

    @Test
    @Transactional
    void findCoreJsonArtifactShouldReturnOnlyCoreType() {
        PolicyVersion version = persistVersion("repo-policy-artifact", true);
        PolicyArtifact coreArtifact = persistArtifact(version.id, ArtifactType.CORE_JSON);
        persistArtifact(version.id, ArtifactType.AST_JSON);

        Optional<PolicyArtifact> artifact = repository.findCoreJsonArtifact(version.id);
        assertTrue(artifact.isPresent(), "应能找到 CORE_JSON 产物");
        assertEquals(coreArtifact.id, artifact.get().id);

        assertTrue(repository.findCoreJsonArtifact(99999L).isEmpty(), "不存在的版本ID应返回空");
        assertTrue(repository.findCoreJsonArtifact(null).isEmpty(), "空版本ID需要返回空");
    }

    @Test
    @Transactional
    void findByTenantShouldListAllCatalogs() {
        persistCatalog(UUID.randomUUID(), "repo-tenant-list", "module-a", "fn-a", null);
        persistCatalog(UUID.randomUUID(), "repo-tenant-list", "module-b", "fn-b", null);
        persistCatalog(UUID.randomUUID(), "repo-tenant-other", "module-a", "fn-a", null);

        List<PolicyCatalog> catalogs = repository.findByTenant("repo-tenant-list");
        assertEquals(2, catalogs.size(), "只应返回指定租户的目录");

        List<PolicyCatalog> empty = repository.findByTenant(null);
        assertTrue(empty.isEmpty(), "空租户参数返回空列表");
    }

    private PolicyCatalog persistCatalog(UUID id, String tenantId, String module, String function, Long defaultVersionId) {
        Instant now = Instant.now();
        entityManager.createNativeQuery(
                "INSERT INTO policy_catalog (" +
                    "id, tenant_id, module_name, function_name, domain, tags, default_version_id, created_at, updated_at" +
                ") VALUES (" +
                    ":id, :tenantId, :moduleName, :functionName, :domain, CAST(:tags AS JSONB), :defaultVersionId, :createdAt, :updatedAt" +
                ")"
            )
            .setParameter("id", id)
            .setParameter("tenantId", tenantId)
            .setParameter("moduleName", module)
            .setParameter("functionName", function)
            .setParameter("domain", "test-domain")
            .setParameter("tags", "{}")
            .setParameter("defaultVersionId", defaultVersionId)
            .setParameter("createdAt", now)
            .setParameter("updatedAt", now)
            .executeUpdate();
        return PolicyCatalog.findById(id);
    }

    private PolicyVersion persistVersion(String policyId, boolean active) {
        Instant now = Instant.now();
        Object identifier = entityManager.createNativeQuery(
                "INSERT INTO policy_versions (" +
                    "policy_id, version, module_name, function_name, content, active, created_at, source_hash, tenant_id" +
                ") VALUES (" +
                    ":policyId, :version, :moduleName, :functionName, :content, :active, :createdAt, :sourceHash, :tenantId" +
                ") RETURNING id"
            )
            .setParameter("policyId", policyId)
            .setParameter("version", System.currentTimeMillis())
            .setParameter("moduleName", "aster.module")
            .setParameter("functionName", "evaluate")
            .setParameter("content", "{}")
            .setParameter("active", active)
            .setParameter("createdAt", now)
            .setParameter("sourceHash", "a".repeat(64))
            .setParameter("tenantId", "demo-tenant")
            .getSingleResult();
        Long id = ((Number) identifier).longValue();
        return PolicyVersion.findById(id);
    }

    private PolicyArtifact persistArtifact(Long versionId, ArtifactType type) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        entityManager.createNativeQuery(
                "INSERT INTO policy_artifacts (" +
                    "id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at" +
                ") VALUES (" +
                    ":id, :versionId, :artifactType, :content, :checksum, CAST(:compilerOpts AS JSONB), :createdAt" +
                ")"
            )
            .setParameter("id", id)
            .setParameter("versionId", versionId)
            .setParameter("artifactType", type.name())
            .setParameter("content", "{\"ir\":true}".getBytes(StandardCharsets.UTF_8))
            .setParameter("checksum", "b".repeat(64))
            .setParameter("compilerOpts", "{}")
            .setParameter("createdAt", now)
            .executeUpdate();
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = id;
        artifact.policyVersionId = versionId;
        artifact.artifactType = type.name();
        return artifact;
    }
}
