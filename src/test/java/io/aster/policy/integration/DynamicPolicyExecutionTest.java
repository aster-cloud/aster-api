package io.aster.policy.integration;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态策略加载集成测试
 *
 * 验证完整流程：数据库加载 → 编译 → 执行 → 缓存
 * 测试 Feature Flag 切换和回退机制
 *
 * 注意：这些测试需要以下条件：
 * 1. 启用动态加载 Feature Flag: aster.policy.dynamic-loading.enabled=true
 * 2. 配置外部数据库连接（Prisma PostgreSQL）
 * 3. 数据库中存在测试数据或使用 @Transactional 创建测试数据
 *
 * 如果测试环境不满足上述条件，测试将被跳过或使用 classpath 回退机制。
 */
@QuarkusTest
class DynamicPolicyExecutionTest {

    @Inject
    PolicyEvaluationService policyEvaluationService;

    @Inject
    PolicySourceRepository policySourceRepository;

    @Inject
    CompiledPolicyCache compiledPolicyCache;

    @ConfigProperty(name = "aster.policy.dynamic-loading.enabled", defaultValue = "false")
    boolean dynamicLoadingEnabled;

    private static final String TEST_TENANT = "test-tenant";
    private static final String TEST_MODULE = "aster.test.sample";
    private static final String TEST_FUNCTION = "testFunction";

    @BeforeEach
    @Transactional
    void setUp() {
        // 清理测试数据
        PolicyArtifact.deleteAll();
        PolicyVersion.deleteAll();
        PolicyCatalog.deleteAll();

        // 清空编译缓存
        compiledPolicyCache.clear();
    }

    /**
     * 测试动态执行：数据库加载 → 编译 → 执行
     */
    @Test
    @Transactional
    void testDynamicExecution() {
        // Given: 准备测试数据
        PolicyCatalog catalog = createTestCatalog();
        catalog.persist();

        PolicyVersion version = createTestVersion(catalog.id);
        version.persist();

        PolicyArtifact artifact = createTestArtifact(version.id);
        artifact.persist();

        // 激活版本
        catalog.defaultVersionId = version.id;
        catalog.persist();

        // When: 执行策略（如果动态加载已启用）
        if (dynamicLoadingEnabled) {
            PolicyEvaluationResult result = policyEvaluationService.evaluatePolicy(
                TEST_TENANT,
                TEST_MODULE,
                TEST_FUNCTION,
                new Object[]{100, 12}
            ).await().indefinitely();

            // Then: 验证结果
            assertThat(result).isNotNull();
            assertThat(result.getResult()).isNotNull();
            assertThat(result.getExecutionTimeMs()).isGreaterThan(0);
        }
    }

    /**
     * 测试缓存机制：首次编译并缓存，后续直接使用缓存
     */
    @Test
    @Transactional
    void testCachingMechanism() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 准备测试数据
        PolicyCatalog catalog = createTestCatalog();
        catalog.persist();

        PolicyVersion version = createTestVersion(catalog.id);
        version.persist();

        PolicyArtifact artifact = createTestArtifact(version.id);
        artifact.persist();

        catalog.defaultVersionId = version.id;
        catalog.persist();

        String versionId = String.valueOf(version.id);

        // When: 首次执行（应该编译并缓存）
        PolicyEvaluationResult result1 = policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证缓存已创建
        Optional<io.aster.policy.cache.CompiledPolicy> cached = compiledPolicyCache.get(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            versionId
        );
        assertThat(cached).isPresent();
        assertThat(cached.get().getVersionId()).isEqualTo(versionId);

        // When: 第二次执行（应该使用缓存）
        PolicyEvaluationResult result2 = policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证两次结果一致
        assertThat(result2).isNotNull();
        assertThat(result2.getResult()).isEqualTo(result1.getResult());
    }

    /**
     * 测试回退机制：数据库查询失败时回退到 classpath
     */
    @Test
    @Transactional
    void testFallbackToClasspath() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 数据库中没有策略数据（模拟查询失败）
        // 不创建任何测试数据

        // When: 执行策略（应该回退到 classpath）
        // 注意：这里使用一个实际存在于 classpath 的策略
        try {
            PolicyEvaluationResult result = policyEvaluationService.evaluatePolicy(
                "default",
                "aster.finance.loan",
                "evaluateLoanEligibility",
                new Object[]{50000, 12, 700, 5000}
            ).await().indefinitely();

            // Then: 验证回退成功
            assertThat(result).isNotNull();
            assertThat(result.getResult()).isNotNull();
        } catch (Exception e) {
            // 如果 classpath 策略也不存在，测试应该失败
            throw new AssertionError("回退到 classpath 失败", e);
        }
    }

    /**
     * 测试缓存失效：版本更新后缓存应该失效
     */
    @Test
    @Transactional
    void testCacheInvalidation() {
        if (!dynamicLoadingEnabled) {
            return; // 仅在动态加载启用时测试
        }

        // Given: 准备测试数据
        PolicyCatalog catalog = createTestCatalog();
        catalog.persist();

        PolicyVersion version1 = createTestVersion(catalog.id);
        version1.persist();

        PolicyArtifact artifact1 = createTestArtifact(version1.id);
        artifact1.persist();

        catalog.defaultVersionId = version1.id;
        catalog.persist();

        // When: 首次执行（缓存版本1）
        policyEvaluationService.evaluatePolicy(
            TEST_TENANT,
            TEST_MODULE,
            TEST_FUNCTION,
            new Object[]{100, 12}
        ).await().indefinitely();

        // Then: 验证缓存存在
        String versionId1 = String.valueOf(version1.id);
        assertThat(compiledPolicyCache.get(TEST_TENANT, TEST_MODULE, TEST_FUNCTION, versionId1))
            .isPresent();

        // When: 失效缓存
        compiledPolicyCache.invalidate(TEST_TENANT, TEST_MODULE, TEST_FUNCTION);

        // Then: 验证缓存已失效
        assertThat(compiledPolicyCache.get(TEST_TENANT, TEST_MODULE, TEST_FUNCTION, versionId1))
            .isEmpty();
    }

    /**
     * 创建测试用的 PolicyCatalog
     */
    private PolicyCatalog createTestCatalog() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.id = UUID.randomUUID();
        catalog.tenantId = TEST_TENANT;
        catalog.moduleName = TEST_MODULE;
        catalog.functionName = TEST_FUNCTION;
        catalog.domain = "test";
        catalog.tags = "{}";
        catalog.createdAt = Instant.now();
        catalog.updatedAt = Instant.now();
        return catalog;
    }

    /**
     * 创建测试用的 PolicyVersion
     */
    private PolicyVersion createTestVersion(UUID catalogId) {
        PolicyVersion version = new PolicyVersion();
        version.policyId = TEST_MODULE + "." + TEST_FUNCTION;
        version.version = System.currentTimeMillis();
        version.moduleName = TEST_MODULE;
        version.functionName = TEST_FUNCTION;
        version.content = "test content";
        version.active = true;
        version.status = VersionStatus.APPROVED;
        version.sourceHash = "test-hash-" + System.currentTimeMillis();
        version.tenantId = TEST_TENANT;
        version.createdAt = Instant.now();
        return version;
    }

    /**
     * 创建测试用的 PolicyArtifact（包含简单的 Core JSON）
     */
    private PolicyArtifact createTestArtifact(Long versionId) {
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = UUID.randomUUID();
        artifact.policyVersionId = versionId;
        artifact.artifactType = "CORE_JSON";

        // 创建一个简单的 Core JSON 用于测试
        String coreJson = """
            {
              "type": "Module",
              "functions": [{
                "name": "testFunction",
                "params": [
                  {"name": "amount", "type": "Number"},
                  {"name": "term", "type": "Number"}
                ],
                "returnType": "Boolean",
                "body": {
                  "type": "Literal",
                  "value": true
                }
              }]
            }
            """;

        artifact.content = coreJson.getBytes(StandardCharsets.UTF_8);
        artifact.contentSha256 = "test-sha256";
        artifact.compilerOpts = "{\"functionSignature\":\"testFunction(amount: Number, term: Number): Boolean\"}";
        artifact.createdAt = Instant.now();
        return artifact;
    }
}
