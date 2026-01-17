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

    @Inject
    protected PolicyVersionService versionService;

    protected PolicyCatalog catalog;

    @BeforeEach
    @Transactional
    void initData() {
        PolicyVersion.deleteAll();
        PolicyCatalog.deleteAll();
        cleanStaticArtifacts();
        catalog = persistCatalog();
    }

    protected Path policyFilePath() {
        return Paths.get("target", "policies", MODULE_NAME, FUNCTION_NAME + ".aster");
    }

    private PolicyCatalog persistCatalog() {
        PolicyCatalog entity = new PolicyCatalog();
        entity.id = UUID.randomUUID();
        entity.tenantId = "tenant-default";
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
