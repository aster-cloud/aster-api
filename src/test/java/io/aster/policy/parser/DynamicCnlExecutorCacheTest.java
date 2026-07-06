package io.aster.policy.parser;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.IdentifierMapping;
import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicCnlExecutorCacheTest {

    private static final String SOURCE = """
        Module cache.probe.

        Define Driver has age as Int.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    @BeforeEach
    void clearCaches() {
        DynamicCnlExecutor.clearCachesForTest();
    }

    @Test
    void same_source_reuses_core_ir_but_not_execution_result() {
        ExecutionResult first = DynamicCnlExecutor.executeWithContext(
            SOURCE, Map.of("driver", Map.of("age", 42)), "main", "en-US", null, false);
        ExecutionResult second = DynamicCnlExecutor.executeWithContext(
            SOURCE, Map.of("driver", Map.of("age", 99)), "main", "en-US", null, false);

        assertThat(first.result()).isEqualTo(42);
        assertThat(second.result()).isEqualTo(99);

        DynamicCnlExecutor.CacheStats stats = DynamicCnlExecutor.cacheStatsForTest();
        assertThat(stats.coreMisses()).isEqualTo(1);
        assertThat(stats.coreHits()).isEqualTo(1);
        assertThat(stats.coreBypasses()).isZero();
        assertThat(stats.coreSize()).isEqualTo(1);
        assertThat(stats.sourceSize()).isEqualTo(1);
    }

    @Test
    void cache_key_covers_alias_locale_vocabulary_and_trust_dimensions() {
        Map<SemanticTokenKind, List<String>> aliasA =
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by"));
        Map<SemanticTokenKind, List<String>> aliasB =
            Map.of(SemanticTokenKind.TIMES, List.of("scaled by"));

        String base = DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, null, true);
        assertThat(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, null, null, null, true))
            .isNotEqualTo(base);
        assertThat(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, aliasA, true))
            .isNotEqualTo(base)
            .isNotEqualTo(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, aliasB, true));
        assertThat(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", customIndex("Fahrer"), null, true))
            .isNotEqualTo(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", customIndex("Pilot"), null, true));
        assertThat(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, aliasA, false))
            .isNotEqualTo(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, aliasA, true));

        DynamicCnlExecutor executor = new DynamicCnlExecutor();
        executor.executeWithTenantContext(
            "tenant-1", SOURCE, Map.of("driver", Map.of("age", 7)), "main",
            "en-US", null, false, aliasA, true);
        executor.executeWithTenantContext(
            "tenant-1", SOURCE, Map.of("driver", Map.of("age", 8)), "main",
            "en-US", null, false, aliasB, true);

        DynamicCnlExecutor.CacheStats stats = DynamicCnlExecutor.cacheStatsForTest();
        assertThat(stats.coreMisses()).isEqualTo(2);
        assertThat(stats.coreHits()).isZero();
        assertThat(stats.coreSize()).isEqualTo(2);
    }

    @Test
    void cache_key_covers_lexicon_state() {
        // lexicon 是可热插拔/下线的：key 必须纳入当前 locale 实际解析用的 lexicon 内容指纹，
        // 否则 lexicon 被替换/禁用后旧 Core IR 仍命中 = stale/错误结果。
        // 不同 locale 对应不同 lexicon（en-US vs zh-CN 的 keywords 内容不同）→ 指纹必须不同。
        String enFp = InProcessCnlParser.lexiconFingerprintForLocale("en-US");
        String zhFp = InProcessCnlParser.lexiconFingerprintForLocale("zh-CN");
        assertThat(enFp).isNotNull();
        assertThat(zhFp).isNotNull();
        assertThat(enFp).isNotEqualTo(zhFp);

        // 指纹进入 cache key：同 source 不同 locale 的 lexicon 指纹不同 → key 不同（不串用）。
        assertThat(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "en-US", null, null, true))
            .isNotEqualTo(DynamicCnlExecutor.coreIrCacheKeyForTest(SOURCE, "zh-CN", null, null, true));
    }

    @Test
    void source_with_import_bypasses_core_ir_cache() {
        String sourceWithImport = """
            Module cache.imports.
            Use risk.Scoring version 1 as Score.

            Rule main given amount as Int, produce Int:
              Return amount.
            """;
        DynamicCnlExecutor executor = new DynamicCnlExecutor();

        ExecutionResult first = executor.executeWithTenantContext(
            "tenant-1", sourceWithImport, Map.of("amount", 41), "main", "en-US", null, false);
        ExecutionResult second = executor.executeWithTenantContext(
            "tenant-1", sourceWithImport, Map.of("amount", 42), "main", "en-US", null, false);

        assertThat(first.result()).isEqualTo(41);
        assertThat(second.result()).isEqualTo(42);

        DynamicCnlExecutor.CacheStats stats = DynamicCnlExecutor.cacheStatsForTest();
        assertThat(stats.coreMisses()).isEqualTo(2);
        assertThat(stats.coreHits()).isZero();
        assertThat(stats.coreBypasses()).isEqualTo(2);
        assertThat(stats.coreSize()).isZero();
    }

    private static IdentifierIndex customIndex(String localizedStruct) {
        DomainVocabulary vocab = new DomainVocabulary(
            "cache.vocab",
            "Cache Vocab",
            "en-US",
            "user",
            List.of(IdentifierMapping.struct("Driver", localizedStruct)),
            List.of(IdentifierMapping.field("age", "alter", "Driver")),
            List.of(),
            List.of(),
            List.of(),
            null
        );
        return IdentifierIndex.build(vocab);
    }
}
