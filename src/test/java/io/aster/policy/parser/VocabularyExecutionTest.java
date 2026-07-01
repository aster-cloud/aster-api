package io.aster.policy.parser;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyLoader;
import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0014 线C：执行端领域词汇翻译回归。
 *
 * 验证发布的策略携带快照领域词汇时，执行端规范化阶段能把用户自定义的本地化
 * 术语翻译为规范化名称；不携带词汇时，含本地化术语的源代码无法解析。
 *
 * 用例采用结构体/字段重命名：源代码用本地化名 `Fahrer`/`alter`（德语「司机/
 * 年龄」），词汇把它们映射到规范化 `Driver`/`age`。规范化后等价于直接用
 * 规范化名编写的源代码。
 */
class VocabularyExecutionTest {

    /** 源代码用本地化术语 Fahrer / alter 编写。 */
    private static final String SOURCE_LOCALIZED = """
        Module insurance.custom.

        Define Fahrer has alter as Int.

        Rule evaluate given driver as Fahrer, produce Int:
          Return driver.alter.
        """;

    /** 等价的规范化源代码（用作对照基线）。 */
    private static final String SOURCE_CANONICAL = """
        Module insurance.custom.

        Define Driver has age as Int.

        Rule evaluate given driver as Driver, produce Int:
          Return driver.age.
        """;

    /** 构建把 Fahrer→Driver、alter→age 的领域词汇索引。 */
    private static IdentifierIndex customIndex() {
        DomainVocabulary vocab = new DomainVocabulary(
            "insurance.custom",
            "Custom Insurance",
            "en-US",
            "user",
            List.of(IdentifierMapping.struct("Driver", "Fahrer")),
            List.of(IdentifierMapping.field("age", "alter", "Driver")),
            List.of(),
            List.of(),
            List.of(),
            null
        );
        return IdentifierIndex.build(vocab);
    }

    @Test
    void canonical_source_baseline_works() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_CANONICAL, context, "evaluate", "en-US");
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_translates_with_vocabulary() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US", customIndex());
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_translates_case_insensitively() {
        // 审查 P1：本地化术语查找须大小写不敏感，且与 TS 引擎等价。
        // 源码用小写 fahrer/alter，词汇定义为 Fahrer/alter（首字母大写）。
        String lowerSource = """
            Module insurance.custom.

            Define fahrer has alter as Int.

            Rule evaluate given driver as fahrer, produce Int:
              Return driver.alter.
            """;
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            lowerSource, context, "evaluate", "en-US", customIndex());
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_translates_via_loadFromMap_with_kind_field() {
        // 本地 E2E 实证的真实路径回归：REST 请求里的 vocabulary 是 Map（cloud/TS
        // 序列化的 DomainVocabulary，每个 mapping 带 kind 字段），经
        // VocabularyLoader.loadFromMap → IdentifierIndex 进执行端。此前 loader
        // 严格 FAIL_ON_UNKNOWN 会因 kind 解析失败 → 静默退化不翻译。
        Map<String, Object> vocabMap = Map.of(
            "id", "insurance.custom",
            "name", "Custom",
            "locale", "en-US",
            "version", "user",
            "structs", List.of(Map.of(
                "canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
            "fields", List.of(Map.of(
                "canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
            "functions", List.of(),
            "enumValues", List.of()
        );
        IdentifierIndex index = IdentifierIndex.build(VocabularyLoader.loadFromMap(vocabMap));

        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US", index);
        assertThat(r.result()).isEqualTo(42);
    }

    @Test
    void localized_source_fails_without_vocabulary() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));
        // 不提供词汇时，本地化术语 Fahrer/alter 不被翻译，源代码语义残缺 →
        // 解析或执行失败（具体异常类型取决于管道，但绝不能返回 42）。
        assertThatThrownBy(() -> DynamicCnlExecutor.executeWithContext(
            SOURCE_LOCALIZED, context, "evaluate", "en-US"))
            .isInstanceOf(RuntimeException.class);
    }
}
