package io.aster.policy.service;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PolicyVersionServiceTest extends BasePolicyVersionServiceTest {

    @Test
    void createVersionShouldPersistSourceAndMetadata() {
        String cnl = "// dual write - primary path";

        PolicyVersion version = versionService.createVersion(catalog.id, cnl, "zh-CN");

        assertNotNull(version.id, "应成功持久化策略版本");
        assertEquals("zh-CN", version.locale, "应写入语言环境");
        assertEquals(catalog.tenantId, version.tenantId, "应继承租户信息");
        assertEquals(MODULE_NAME + "." + FUNCTION_NAME, version.policyId, "policyId 应由模块与函数组成");
        assertFalse(version.active, "新版本默认保持非活跃，等待激活");

        PolicyVersion stored = PolicyVersion.findById(version.id);
        assertEquals(cnl, stored.content, "应保存 CNL 源码");
    }

    @Test
    void createVersionShouldWriteStaticFileWhenDualWriteEnabled() throws IOException {
        String cnl = "// dual write file target";

        versionService.createVersion(catalog.id, cnl, "en-US");

        Path policyFile = policyFilePath();
        assertTrue(Files.exists(policyFile), "启用双写时应生成静态兜底文件");
        assertEquals(cnl, Files.readString(policyFile, StandardCharsets.UTF_8), "静态文件内容应与源码一致");
    }

    @Test
    void activateVersionShouldUpdateCatalogAndVersionState() {
        PolicyVersion version = versionService.createVersion(catalog.id, "// activate me", "en-US");

        versionService.submitForApproval(version.id, "author");
        versionService.approveVersion(version.id, "approver");
        versionService.activateVersion(version.id, "activator");

        PolicyVersion activated = PolicyVersion.findById(version.id);
        assertTrue(activated.active, "激活后应标记为活跃");
        assertNotNull(activated.activatedAt, "激活时间戳应被记录");
        assertEquals("activator", activated.activatedBy, "应记录激活操作人");

        PolicyCatalog refreshed = PolicyCatalog.findById(catalog.id);
        assertEquals(version.id, refreshed.defaultVersionId, "Catalog 默认版本应同步更新");
    }

    /**
     * 回滚到已审批的历史版本：必须同步 catalog.defaultVersionId（G5 一致性修复）。
     *
     * <p>否则 active 指向新版本但 catalog 仍指旧版本，findActiveVersion(catalogId)
     * 的 id==defaultVersionId AND active==true 两条件不交集 → /evaluate 读空、评估失败。
     */
    @Test
    void rollbackToApprovedVersionShouldSyncCatalogDefault() {
        // v1：审批 + 激活（成为初始活跃版本，catalog.defaultVersionId=v1）
        PolicyVersion v1 = versionService.createVersion(catalog.id, "// rule v1", "en-US");
        versionService.submitForApproval(v1.id, "author");
        versionService.approveVersion(v1.id, "approver");
        versionService.activateVersion(v1.id, "activator");

        // v2：审批 + 激活（活跃切到 v2，catalog.defaultVersionId=v2）
        PolicyVersion v2 = versionService.createVersion(catalog.id, "// rule v2", "en-US");
        versionService.submitForApproval(v2.id, "author");
        versionService.approveVersion(v2.id, "approver");
        versionService.activateVersion(v2.id, "activator");
        assertEquals(v2.id, PolicyCatalog.<PolicyCatalog>findById(catalog.id).defaultVersionId,
            "前置：激活 v2 后 catalog 应指向 v2");

        // 回滚到 v1（已审批）→ 活跃回到 v1，且 catalog.defaultVersionId 必须同步回 v1
        versionService.rollbackToVersion(v1.policyId, v1.version, "rollbacker");

        PolicyVersion v1After = PolicyVersion.findById(v1.id);
        PolicyVersion v2After = PolicyVersion.findById(v2.id);
        assertTrue(v1After.active, "回滚后 v1 应为活跃");
        assertFalse(v2After.active, "回滚后 v2 应被停用");

        PolicyCatalog refreshed = PolicyCatalog.findById(catalog.id);
        assertEquals(v1.id, refreshed.defaultVersionId,
            "回滚必须同步 catalog.defaultVersionId 回 v1，否则 /evaluate 读空");
    }

    /**
     * 回滚到未审批（DRAFT）版本必须被拒绝（G5 发布治理：堵未审批旁路激活）。
     */
    @Test
    void rollbackToUnapprovedVersionShouldThrow() {
        // 一个已审批激活的基线版本
        PolicyVersion approved = versionService.createVersion(catalog.id, "// approved", "en-US");
        versionService.submitForApproval(approved.id, "author");
        versionService.approveVersion(approved.id, "approver");
        versionService.activateVersion(approved.id, "activator");

        // 一个从未审批的 DRAFT 版本（模拟 AI 草稿）
        PolicyVersion draft = versionService.createVersion(catalog.id, "// unapproved draft", "en-US");
        assertEquals(io.aster.policy.entity.VersionStatus.DRAFT, draft.status, "前置：草稿应为 DRAFT");

        // 回滚到未审批草稿 → 抛 IllegalStateException，活跃版本不变
        assertThrows(IllegalStateException.class,
            () -> versionService.rollbackToVersion(draft.policyId, draft.version, "attacker"),
            "未审批版本不得经回滚旁路激活");

        PolicyCatalog refreshed = PolicyCatalog.findById(catalog.id);
        assertEquals(approved.id, refreshed.defaultVersionId, "回滚被拒后 catalog 仍指向已审批版本");
    }
}

@QuarkusTest
@TestProfile(PolicyVersionServiceDualWriteDisabledTest.DualWriteDisabledProfile.class)
class PolicyVersionServiceDualWriteDisabledTest extends BasePolicyVersionServiceTest {

    @Test
    void shouldSkipStaticFileWhenFeatureDisabled() {
        versionService.createVersion(catalog.id, "// skip dual write", "en-US");

        assertFalse(Files.exists(policyFilePath()), "关闭双写后不应生成静态兜底文件");
    }

    public static class DualWriteDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("aster.policy.dual-write.enabled", "false");
        }
    }
}

abstract class BasePolicyVersionServiceTest {

    static final String MODULE_NAME = "aster.finance.loan";
    static final String FUNCTION_NAME = "evaluateLoanEligibility";
    static final String TEST_TENANT = "tenant-default";

    @Inject
    protected PolicyVersionService versionService;

    protected PolicyCatalog catalog;

    @BeforeEach
    @Transactional
    void initData() {
        // 仅清理本测试创建的数据（按租户隔离），保留 V99 种子数据
        // 1. 先清除测试租户的 PolicyCatalog 的 defaultVersionId 外键引用
        PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TEST_TENANT);
        // 2. 删除测试租户的 PolicyArtifact（通过关联 PolicyVersion）
        io.aster.policy.entity.PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TEST_TENANT);
        // 3. 删除测试租户的 PolicyVersion
        PolicyVersion.delete("tenantId = ?1", TEST_TENANT);
        // 4. 最后删除测试租户的 PolicyCatalog
        PolicyCatalog.delete("tenantId = ?1", TEST_TENANT);
        cleanStaticArtifacts();
        catalog = persistCatalog();
    }

    protected Path policyFilePath() {
        return Paths.get("target", "policies", MODULE_NAME, FUNCTION_NAME + ".aster");
    }

    private PolicyCatalog persistCatalog() {
        PolicyCatalog entity = new PolicyCatalog();
        entity.id = UUID.randomUUID();
        entity.tenantId = TEST_TENANT;
        entity.moduleName = MODULE_NAME;
        entity.functionName = FUNCTION_NAME;
        entity.domain = "finance";
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.persist();
        return entity;
    }

    private void cleanStaticArtifacts() {
        Path file = policyFilePath();
        try {
            Files.deleteIfExists(file);
            Path moduleDir = file.getParent();
            if (moduleDir != null) {
                deleteDirIfEmpty(moduleDir);
                Path policiesDir = moduleDir.getParent();
                if (policiesDir != null) {
                    deleteDirIfEmpty(policiesDir);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("清理策略静态文件失败", e);
        }
    }

    private void deleteDirIfEmpty(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (!children.findFirst().isPresent()) {
                Files.deleteIfExists(dir);
            }
        }
    }
}
