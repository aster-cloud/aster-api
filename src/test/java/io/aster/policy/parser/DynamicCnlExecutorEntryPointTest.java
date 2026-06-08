package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicCnlExecutorEntryPointTest {

    private static final String SINGLE_RULE_SOURCE = """
        Module entry.single.

        Define Driver has age as Int.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    private static final String MULTI_RULE_SOURCE = """
        Module entry.multi.

        Define Driver has age as Int.

        Rule helper given driver as Driver, produce Int:
          Return driver.age.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    // 多 Rule 本应 Ambiguous，但 @entry 标记了入口（ADR 0015 阶段2）。
    // @entry 支持与 Rule 同行（@entry Rule main）或独立成行（@entry\nRule main，
    // grammar (annotation NEWLINE*)* RULE，aster-lang-core#9）。
    private static final String ENTRY_ANNOTATED_SOURCE = """
        Module entry.annotated.

        Define Driver has age as Int.

        Rule helper given driver as Driver, produce Int:
          Return driver.age.

        @entry Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    // @entry 独立成行变体（与 ENTRY_ANNOTATED_SOURCE 等价，仅注解换行）
    private static final String STANDALONE_ENTRY_SOURCE = """
        Module entry.standalone.

        Define Driver has age as Int.

        Rule helper given driver as Driver, produce Int:
          Return driver.age.

        @entry
        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    @Test
    void single_rule_without_function_executes_selected_rule() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));

        ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            SINGLE_RULE_SOURCE, context, null, "en-US", null, false);

        assertThat(result.functionName()).isEqualTo("main");
        assertThat(result.result()).isEqualTo(42);
    }

    @Test
    void multiple_rules_without_function_throws_ambiguous_entry_exception() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));

        assertThatThrownBy(() -> DynamicCnlExecutor.executeWithContext(
            MULTI_RULE_SOURCE, context, null, "en-US", null, false))
            .isInstanceOf(DynamicCnlExecutor.AmbiguousEntryException.class)
            .satisfies(error -> {
                DynamicCnlExecutor.AmbiguousEntryException ambiguous =
                    (DynamicCnlExecutor.AmbiguousEntryException) error;
                assertThat(ambiguous.getCandidates()).containsExactly("helper", "main");
            });
    }

    @Test
    void entry_annotation_resolves_ambiguity_and_executes_marked_rule() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));

        // 多 Rule 无显式 functionName，但 @entry 标记 main → 不再 Ambiguous，执行 main
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            ENTRY_ANNOTATED_SOURCE, context, null, "en-US", null, false);

        assertThat(result.functionName()).isEqualTo("main");
        assertThat(result.result()).isEqualTo(42);
    }

    @Test
    void standalone_entry_line_resolves_ambiguity_end_to_end() {
        Map<String, Object> context = Map.of("driver", Map.of("age", 42));

        // @entry 独立成行（经 Canonicalizer + grammar）同样解决歧义并执行 main
        ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            STANDALONE_ENTRY_SOURCE, context, null, "en-US", null, false);

        assertThat(result.functionName()).isEqualTo("main");
        assertThat(result.result()).isEqualTo(42);
    }
}
