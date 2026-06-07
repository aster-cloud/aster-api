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
}
