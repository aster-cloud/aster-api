package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR：逻辑 and/or 运算符端到端（经 Canonicalizer + grammar + GraalVM Truffle 执行）。 */
class LogicalOpE2ETest {

    private static final String AND_SOURCE = """
        Module logic.conjunct.

        Define Applicant has age as Int, income as Int.

        Rule main given applicant as Applicant, produce Bool:
          If applicant.age at least 18 and applicant.income at least 30000
            Return true.
          Return false.
        """;

    private static final String OR_SOURCE = """
        Module logic.disjunct.

        Define Account has tier as Text.

        Rule main given account as Account, produce Bool:
          If account.tier equals to "gold" or account.tier equals to "platinum"
            Return true.
          Return false.
        """;

    @Test
    void and_bothTrue_returnsTrue() {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            AND_SOURCE, Map.of("applicant", Map.of("age", 25, "income", 40000)), null, "en-US", null, false);
        assertThat(r.result()).isEqualTo(true);
    }

    @Test
    void and_oneFalse_returnsFalse() {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            AND_SOURCE, Map.of("applicant", Map.of("age", 25, "income", 20000)), null, "en-US", null, false);
        assertThat(r.result()).isEqualTo(false);
    }

    @Test
    void or_oneTrue_returnsTrue() {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            OR_SOURCE, Map.of("account", Map.of("tier", "platinum")), null, "en-US", null, false);
        assertThat(r.result()).isEqualTo(true);
    }

    @Test
    void or_noneTrue_returnsFalse() {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            OR_SOURCE, Map.of("account", Map.of("tier", "silver")), null, "en-US", null, false);
        assertThat(r.result()).isEqualTo(false);
    }
}
