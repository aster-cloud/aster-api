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

        // 另一个不同 function 的策略，前缀不同——invalidate 不应误删它
        CompiledPolicy unrelated = new CompiledPolicy("100", "hashX", "{\"other\":1}", metadata);
        cache.put(tenantId, module, "otherFunc", unrelated);

        // When
        cache.invalidate(tenantId, module, function);

        // Then：目标策略的所有版本被删
        assertThat(cache.get(tenantId, module, function, "100")).isEmpty();
        assertThat(cache.get(tenantId, module, function, "101")).isEmpty();
        // 前缀不同的无关策略保留（证明 Caffeine asMap 前缀扫描删除是选择性的、只删匹配项）
        assertThat(cache.get(tenantId, module, "otherFunc", "100")).isPresent();
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

    /** 生成指定字符长度的 coreJson 占位串（纯 ASCII，模拟真实编译产物体积）。 */
    private static String coreJsonOfSize(int chars) {
        return "{".concat("x".repeat(Math.max(0, chars - 2))).concat("}");
    }

    @Test
    void shouldEvictWhenExceedingByteBudget() {
        // Given：堆预算 100KB 的小缓存（构造注入 max-bytes=100_000，关闭按访问过期）。
        CompiledPolicyCache bounded = new CompiledPolicyCache(100_000L, 0);
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        // When：写入 50 个各 ~10KB coreJson 的策略 = 总 ~500KB，5× 预算。
        for (int i = 0; i < 50; i++) {
            CompiledPolicy p = new CompiledPolicy(
                    String.valueOf(i), "hash" + i, coreJsonOfSize(10_000), metadata);
            bounded.put("tenant1", "module1", "func" + i, p);
        }

        // Then：按字节 weight 驱逐，驻留条数被钳在 ~预算/条大小（100KB / ~10KB ≈ 10 条），
        // 绝不会保留全部 50 个——证明按字节预算有界。
        long size = bounded.getStats().size();
        assertThat(size).isLessThan(50L);
        assertThat(size).isLessThanOrEqualTo(12L); // 预算 100KB / 10KB/条 ≈ 10 + Caffeine 容差
    }

    @Test
    void shouldEvictMoreLargePoliciesThanSmall() {
        // 证明"按字节非按条数"：同预算下，大策略容纳的【条数】显著少于小策略。
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        // 预算 200KB，写 100 个大策略（各 ~20KB）
        CompiledPolicyCache largeCache = new CompiledPolicyCache(200_000L, 0);
        for (int i = 0; i < 100; i++) {
            largeCache.put("t", "m", "f" + i,
                    new CompiledPolicy(String.valueOf(i), "h" + i, coreJsonOfSize(20_000), metadata));
        }
        long largeCount = largeCache.getStats().size();

        // 同预算 200KB，写 100 个小策略（各 ~2KB）
        CompiledPolicyCache smallCache = new CompiledPolicyCache(200_000L, 0);
        for (int i = 0; i < 100; i++) {
            smallCache.put("t", "m", "f" + i,
                    new CompiledPolicy(String.valueOf(i), "h" + i, coreJsonOfSize(2_000), metadata));
        }
        long smallCount = smallCache.getStats().size();

        // 大策略容纳条数应远少于小策略（~10 vs ~100）——按字节自适应的核心证据。
        assertThat(largeCount).isLessThan(smallCount);
        assertThat(largeCount).isLessThanOrEqualTo(12L);
    }

    @Test
    void shouldCountNonLatin1CoreJsonAsUtf16() {
        // 多语言策略场景（Codex 审查 P1）：coreJson 含非 Latin1 字符（中文）时，
        // Java String 退化为 UTF-16 存储 2 字节/字符——weight 必须按 2× 计，否则堆低估一半。
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        // 预算 100KB。ASCII coreJson 各 ~5KB → 应容纳更多；含中文的 coreJson 同字符数
        // 实际堆翻倍 → 应容纳约一半条数。
        int chars = 5_000;
        String asciiCore = coreJsonOfSize(chars);
        String cjkCore = "{".concat("界".repeat(chars - 2)).concat("}"); // 全中文，UTF-16

        CompiledPolicyCache asciiCache = new CompiledPolicyCache(100_000L, 0);
        CompiledPolicyCache cjkCache = new CompiledPolicyCache(100_000L, 0);
        for (int i = 0; i < 60; i++) {
            asciiCache.put("t", "m", "f" + i, new CompiledPolicy(String.valueOf(i), "h", asciiCore, metadata));
            cjkCache.put("t", "m", "f" + i, new CompiledPolicy(String.valueOf(i), "h", cjkCore, metadata));
        }
        long asciiCount = asciiCache.getStats().size();
        long cjkCount = cjkCache.getStats().size();

        // 中文 coreJson 每条 weight ≈ ASCII 的 2×（UTF-16）→ 容纳条数约一半。
        // 断言 cjk 明显少于 ascii，证明非 Latin1 按 2 字节/字符正确计入。
        assertThat(cjkCount).isLessThan(asciiCount);
        // ASCII ~5KB/条 → ~19 条；CJK ~10KB/条 → ~9 条。给宽松边界避免 Caffeine 近似抖动。
        assertThat(cjkCount).isLessThanOrEqualTo(asciiCount * 3 / 5);
    }

    @Test
    void shouldFallBackWhenMaxBytesNonPositive() {
        // 配置错成 0（Codex 审查 P3）：应回退默认预算（96MB）而非静默变成 1 字节缓存。
        CompiledPolicyCache zeroCache = new CompiledPolicyCache(0L, 0);
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        // 写 50 个小策略——若真变成 1 字节缓存会几乎全被逐；回退默认后应全部保留。
        for (int i = 0; i < 50; i++) {
            zeroCache.put("t", "m", "f" + i,
                    new CompiledPolicy(String.valueOf(i), "h" + i, coreJsonOfSize(500), metadata));
        }
        assertThat(zeroCache.getStats().size()).isEqualTo(50L);
    }

    @Test
    void shouldNotEvictWithinByteBudget() {
        // Given：默认预算（96MB）远大于测试数据量
        CompiledPolicyCache defaultCache = new CompiledPolicyCache();
        CompilationMetadata metadata = new CompilationMetadata("sig", null, "Boolean");

        // When：写入 100 个小策略（各 ~1KB，总 ~100KB ≪ 96MB）
        for (int i = 0; i < 100; i++) {
            CompiledPolicy p = new CompiledPolicy(
                    String.valueOf(i), "h" + i, coreJsonOfSize(1_000), metadata);
            defaultCache.put("t", "m", "f" + i, p);
        }

        // Then：未达预算，全部保留
        assertThat(defaultCache.getStats().size()).isEqualTo(100L);
        assertThat(defaultCache.get("t", "m", "f0", "0")).isPresent();
        assertThat(defaultCache.get("t", "m", "f99", "99")).isPresent();
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
