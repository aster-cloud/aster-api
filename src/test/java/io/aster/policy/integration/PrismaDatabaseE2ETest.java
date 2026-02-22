package io.aster.policy.integration;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部 Prisma 数据库 E2E 测试
 *
 * 验证：
 * 1. 数据库连接成功
 * 2. Flyway 迁移成功创建表
 * 3. 动态策略加载从数据库读取
 */
@QuarkusTest
@TestProfile(PrismaDatabaseE2ETest.PrismaProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PrismaDatabaseE2ETest {

    public static class PrismaProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prisma";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of();
        }
    }

    @Inject
    EntityManager entityManager;

    private static final String TEST_TENANT = "e2e-prisma-test";
    private static final String TEST_MODULE = "aster.e2e.test";
    private static final String TEST_FUNCTION = "testPolicy";
    private static UUID testCatalogId;
    private static Long testVersionId;

    @Test
    @Order(0)
    @Transactional
    void test0_cleanupStaleData() {
        // 清理上一次测试可能遗留的数据
        // 使用原生 SQL 确保可靠清除外键引用
        entityManager.createNativeQuery(
            "UPDATE policy_catalog SET default_version_id = NULL WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        entityManager.createNativeQuery(
            "DELETE FROM policy_versions WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        entityManager.createNativeQuery(
            "DELETE FROM policy_catalog WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        System.out.println("✅ 清理残留测试数据完成");
    }

    @Test
    @Order(1)
    void test1_databaseConnectionSuccessful() {
        // 执行简单查询验证连接
        Object result = entityManager.createNativeQuery("SELECT 1").getSingleResult();
        assertThat(result).isNotNull();
        System.out.println("✅ 数据库连接成功");
    }

    @Test
    @Order(2)
    void test2_flywayMigrationSuccessful() {
        // 验证 policy_versions 表存在
        List<?> tables = entityManager.createNativeQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_name = 'policy_versions'"
        ).getResultList();
        assertThat(tables).isNotEmpty();
        System.out.println("✅ policy_versions 表存在");

        // 验证 policy_catalog 表存在
        tables = entityManager.createNativeQuery(
            "SELECT table_name FROM information_schema.tables WHERE table_name = 'policy_catalog'"
        ).getResultList();
        assertThat(tables).isNotEmpty();
        System.out.println("✅ policy_catalog 表存在");
    }

    @Test
    @Order(3)
    @Transactional
    void test3_createPolicyCatalog() {
        testCatalogId = UUID.randomUUID();
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.id = testCatalogId;
        catalog.tenantId = TEST_TENANT;
        catalog.moduleName = TEST_MODULE;
        catalog.functionName = TEST_FUNCTION;
        catalog.domain = "test";
        catalog.createdAt = Instant.now();
        catalog.updatedAt = Instant.now();
        catalog.persist();

        System.out.println("✅ 创建策略目录: " + testCatalogId);
    }

    @Test
    @Order(4)
    @Transactional
    void test4_createPolicyVersion() {
        String cnlSource = """
            Module test.policy.

            a Request has amount.
            a Decision has approved, reason.

            Rule testPolicy given request as Request:
              If request.amount greater than 1000
                Return Decision with approved set to false, reason set to "Amount too high".
              Return Decision with approved set to true, reason set to "Approved".
            """;

        PolicyVersion version = new PolicyVersion();
        version.policyId = TEST_MODULE + "." + TEST_FUNCTION;
        version.moduleName = TEST_MODULE;
        version.functionName = TEST_FUNCTION;
        version.content = cnlSource;
        version.locale = "en-US";
        version.tenantId = TEST_TENANT;
        version.active = true;
        version.createdBy = "e2e-test";
        version.createdAt = Instant.now();
        version.notes = "E2E test policy";
        // 设置必需的字段
        version.version = Instant.now().toEpochMilli();
        version.sourceHash = computeSourceHash(cnlSource);
        version.persist();

        testVersionId = version.id;
        System.out.println("✅ 创建策略版本: " + testVersionId);

        // 更新 catalog 的默认版本
        PolicyCatalog catalog = PolicyCatalog.findById(testCatalogId);
        if (catalog != null) {
            catalog.defaultVersionId = testVersionId;
            catalog.persist();
            System.out.println("✅ 更新目录默认版本");
        }
    }

    @Test
    @Order(5)
    void test5_queryPolicyVersion() {
        PolicyVersion version = PolicyVersion.findActiveVersion(TEST_MODULE + "." + TEST_FUNCTION);
        assertThat(version).isNotNull();
        assertThat(version.content).contains("testPolicy");
        assertThat(version.locale).isEqualTo("en-US");
        System.out.println("✅ 查询活跃策略版本成功");
    }

    @Test
    @Order(6)
    void test6_queryCatalog() {
        List<PolicyCatalog> catalogs = PolicyCatalog.list("tenantId", TEST_TENANT);
        assertThat(catalogs).isNotEmpty();
        assertThat(catalogs.get(0).moduleName).isEqualTo(TEST_MODULE);
        System.out.println("✅ 查询策略目录成功");
    }

    @Test
    @Order(99)
    @Transactional
    void test99_cleanup() {
        // 使用原生 SQL 确保可靠清除外键引用
        entityManager.createNativeQuery(
            "UPDATE policy_catalog SET default_version_id = NULL WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        entityManager.createNativeQuery(
            "DELETE FROM policy_versions WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        entityManager.createNativeQuery(
            "DELETE FROM policy_catalog WHERE tenant_id = ?1"
        ).setParameter(1, TEST_TENANT).executeUpdate();

        System.out.println("✅ 清理测试数据完成");
    }

    /**
     * 计算策略内容的 SHA-256 哈希
     */
    private static String computeSourceHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute source hash", e);
        }
    }
}
