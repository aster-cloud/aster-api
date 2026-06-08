package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** tier1-equivalence/policies 22-24 逻辑运算符 fixture 的 Java 引擎执行验证（与 TS 引擎对齐）。 */
class LogicalParityFixtureTest {

    private static final String F22_AND = """
        Module dual.engine.logical.conjunction.

        Rule both given x, y, produce:
          Return x at least 18 and y at least 30000.
        """;

    private static final String F23_OR = """
        Module dual.engine.logical.disjunction.

        Rule either given tier, produce:
          Return tier equals to "gold" or tier equals to "platinum".
        """;

    // 参数名刻意避开 en-US 冠词 a/an/the —— Canonicalizer.removeArticles 会吞掉
    // 名为 a 的标识符（既有 Canonicalizer 行为，与逻辑运算符无关）。
    private static final String F24_PRECEDENCE = """
        Module dual.engine.logical.precedence.

        Rule mixed given p, q, r, produce:
          Return p equals to 1 or q equals to 2 and r equals to 3.
        """;

    @Test
    void f22_and() {
        assertThat(exec(F22_AND, "both", Map.of("x", 20, "y", 40000))).isEqualTo(true);
        assertThat(exec(F22_AND, "both", Map.of("x", 20, "y", 1000))).isEqualTo(false);
    }

    @Test
    void f23_or() {
        assertThat(exec(F23_OR, "either", Map.of("tier", "gold"))).isEqualTo(true);
        assertThat(exec(F23_OR, "either", Map.of("tier", "bronze"))).isEqualTo(false);
    }

    @Test
    void f24_precedence_andBindsTighterThanOr() {
        // p==1 OR (q==2 AND r==3) —— and 优先级高于 or
        assertThat(exec(F24_PRECEDENCE, "mixed", Map.of("p", 1, "q", 0, "r", 0))).isEqualTo(true);
        assertThat(exec(F24_PRECEDENCE, "mixed", Map.of("p", 0, "q", 2, "r", 3))).isEqualTo(true);
        assertThat(exec(F24_PRECEDENCE, "mixed", Map.of("p", 0, "q", 2, "r", 0))).isEqualTo(false);
    }

    private Object exec(String src, String fn, Map<String, Object> ctx) {
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(src, ctx, fn, "en-US", null, false);
        return r.result();
    }
}
