package io.aster.policy.cache;

import io.aster.policy.compiler.CompilationMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CompiledPolicyCache 单元测试
 * 验证编译缓存的正确性
 */
class CompiledPolicyCacheTest {

    private CompiledPolicyCache cache;

    @BeforeEach
    void setUp() {
        cache = new CompiledPolicyCache();
    }

    @Test
    void shouldCacheCompiledPolicy() {
        // Given
        String tenantId = "tenant1";
        String module = "aster.finance.loan";
        String function = "evaluate";
        String versionId = "100";

        CompilationMetadata metadata = new CompilationMetadata(
            "evaluate(amount: Number, term: Number): Boolean",
            null,
            "Boolean"
        );

        CompiledPolicy policy = new CompiledPolicy(
            versionId,
            "hash123",
            "{\"type\":\"core\"}",
            metadata
        );

        // When
        cache.put(tenantId, module, function, policy);
        Optional<CompiledPolicy> result = cache.get(tenantId, module, function, versionId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getVersionId()).isEqualTo(versionId);
        assertThat(result.get().getSourceHash()).isEqualTo("hash123");
        assertThat(result.get().getCoreJson()).isEqualTo("{\"type\":\"core\"}");
    }

    @Test
    void shouldReturnEmptyWhenNotCached() {
        // When
        Optional<CompiledPolicy> result = cache.get("tenant1", "module", "function", "v1");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldDifferentiateDifferentVersions() {
        // Given
        String tenantId = "tenant1";
        String module = "aster.finance.loan";
        String function = "evaluate";

        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        CompiledPolicy policyV1 = new CompiledPolicy("100", "hash1", "{\"v\":1}", metadata);
        CompiledPolicy policyV2 = new CompiledPolicy("101", "hash2", "{\"v\":2}", metadata);

        // When
        cache.put(tenantId, module, function, policyV1);
        cache.put(tenantId, module, function, policyV2);

        // Then
        Optional<CompiledPolicy> resultV1 = cache.get(tenantId, module, function, "100");
        Optional<CompiledPolicy> resultV2 = cache.get(tenantId, module, function, "101");

        assertThat(resultV1).isPresent();
        assertThat(resultV1.get().getCoreJson()).isEqualTo("{\"v\":1}");

        assertThat(resultV2).isPresent();
        assertThat(resultV2.get().getCoreJson()).isEqualTo("{\"v\":2}");
    }

    @Test
    void shouldInvalidateAllVersionsOfPolicy() {
        // Given
        String tenantId = "tenant1";
        String module = "aster.finance.loan";
        String function = "evaluate";

        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        CompiledPolicy policyV1 = new CompiledPolicy("100", "hash1", "{\"v\":1}", metadata);
        CompiledPolicy policyV2 = new CompiledPolicy("101", "hash2", "{\"v\":2}", metadata);

        cache.put(tenantId, module, function, policyV1);
        cache.put(tenantId, module, function, policyV2);

        // When
        cache.invalidate(tenantId, module, function);

        // Then
        assertThat(cache.get(tenantId, module, function, "100")).isEmpty();
        assertThat(cache.get(tenantId, module, function, "101")).isEmpty();
    }

    @Test
    void shouldClearAllCache() {
        // Given
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        CompiledPolicy policy1 = new CompiledPolicy("100", "hash1", "{}", metadata);
        CompiledPolicy policy2 = new CompiledPolicy("200", "hash2", "{}", metadata);

        cache.put("tenant1", "module1", "func1", policy1);
        cache.put("tenant2", "module2", "func2", policy2);

        assertThat(cache.getStats().size()).isEqualTo(2);

        // When
        cache.clear();

        // Then
        assertThat(cache.getStats().size()).isEqualTo(0);
        assertThat(cache.get("tenant1", "module1", "func1", "100")).isEmpty();
        assertThat(cache.get("tenant2", "module2", "func2", "200")).isEmpty();
    }

    @Test
    void shouldReportCorrectStats() {
        // Given
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        CompiledPolicy policy1 = new CompiledPolicy("100", "hash1", "{}", metadata);
        CompiledPolicy policy2 = new CompiledPolicy("101", "hash2", "", metadata);
        CompiledPolicy policy3 = new CompiledPolicy("102", "hash3", "{}", metadata);

        // When
        cache.put("tenant1", "module1", "func1", policy1);
        cache.put("tenant1", "module1", "func1", policy2);
        cache.put("tenant1", "module2", "func2", policy3);

        // Then
        assertThat(cache.getStats().size()).isEqualTo(3);
    }

    @Test
    void shouldHandleMultipleTenants() {
        // Given
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        CompiledPolicy policy1 = new CompiledPolicy("100", "hash1", "{\"tenant\":1}", metadata);
        CompiledPolicy policy2 = new CompiledPolicy("100", "hash2", "{\"tenant\":2}", metadata);

        // When
        cache.put("tenant1", "module", "function", policy1);
        cache.put("tenant2", "module", "function", policy2);

        // Then
        Optional<CompiledPolicy> result1 = cache.get("tenant1", "module", "function", "100");
        Optional<CompiledPolicy> result2 = cache.get("tenant2", "module", "function", "100");

        assertThat(result1).isPresent();
        assertThat(result1.get().getCoreJson()).isEqualTo("{\"tenant\":1}");

        assertThat(result2).isPresent();
        assertThat(result2.get().getCoreJson()).isEqualTo("{\"tenant\":2}");
    }
}
