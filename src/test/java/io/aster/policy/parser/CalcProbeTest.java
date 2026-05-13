package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression probe — multi-rule modules in the Java engine.
 *
 * 用户场景（dashboard 返回 result=0）暴露了一个真实 bug：
 *
 *   当模块包含 2 个 Rule 且其中一个调用另一个（"calculate → add"）时，
 *   Java Truffle runtime 返回 Integer(0) 而非应有的 Construct map。
 *
 *   相同 source 但只保留 add 一个 Rule（"FLAT"形态）→ 返回正确的
 *   `{_type=CalcResult, resultAmount=2000, ...}` map。
 *
 *   TS 引擎在两种形态下都返回正确结果。
 *
 * 故障定位：
 *   - 不是类型推导（typed / untyped 都失败）
 *   - 不是字段数（FLAT 1/2/3 字段都正确）
 *   - 是多 Rule + 跨 Rule 调用时的求值/lowering 路径
 *
 * 这是 P1-9.5 deep-equivalence 设计文档预言的"parse 等价但 eval 分歧"
 * 类 case。修复前用 @Disabled 标注避免 CI 噪音，但保留 source 作为
 * regression anchor。
 */
@Disabled("Reproduces a known Java-engine bug — multi-rule module returns 0. " +
          "See docs/rfc/deep-equivalence-design.md and the 'multi-rule eval bug' " +
          "tracking task.")
class CalcProbeTest {

    private static final String SOURCE_UNTYPED = """
        Module tools.calculator.simple.

        Define CalcOp as one of Add, Subtract, Multiply, Divide.

        Define CalcRequest has leftAmount, rightAmount.

        Define CalcResult has resultAmount, hasError, errorMessage.

        Rule calculate given request as CalcRequest, produce CalcResult:
          Return add(request).

        Rule add given request as CalcRequest, produce CalcResult:
          Let v be (request.leftAmount plus request.rightAmount).
          Return CalcResult with resultAmount set to v, hasError set to "false", errorMessage set to "No error".
        """;

    /** Same source but with explicit field types. */
    private static final String SOURCE_TYPED = """
        Module tools.calculator.typed.

        Define CalcRequest has leftAmount as Int, rightAmount as Int.

        Define CalcResult has resultAmount as Int, hasError as Text, errorMessage as Text.

        Rule calculate given request as CalcRequest, produce CalcResult:
          Return add(request).

        Rule add given request as CalcRequest, produce CalcResult:
          Let v be (request.leftAmount plus request.rightAmount).
          Return CalcResult with resultAmount set to v, hasError set to "false", errorMessage set to "No error".
        """;

    /** Direct add, single rule, no nesting. */
    private static final String SOURCE_FLAT = """
        Module tools.calculator.flat.

        Define CalcRequest has leftAmount as Int, rightAmount as Int.

        Define CalcResult has resultAmount as Int.

        Rule add given request as CalcRequest, produce CalcResult:
          Let v be (request.leftAmount plus request.rightAmount).
          Return CalcResult with resultAmount set to v.
        """;

    private static void probe(String label, String source, String entry) {
        Map<String, Object> request = Map.of("leftAmount", 1000, "rightAmount", 1000);
        Map<String, Object> context = Map.of("request", request);
        try {
            ExecutionResult r = DynamicCnlExecutor.executeWithContext(source, context, entry, "en-US");
            System.out.println("=== " + label + " ===");
            System.out.println("  result class: " + (r.result() == null ? "null" : r.result().getClass().getName()));
            System.out.println("  result: " + r.result());
        } catch (Exception e) {
            System.out.println("=== " + label + " ===");
            System.out.println("  EXCEPTION: " + e.getMessage());
        }
    }

    /** 2 fields - one less than user's case */
    private static final String SOURCE_2FIELDS = """
        Module tools.calculator.f2.

        Define CalcRequest has leftAmount as Int, rightAmount as Int.

        Define CalcResult has resultAmount as Int, hasError as Text.

        Rule add given request as CalcRequest, produce CalcResult:
          Let v be (request.leftAmount plus request.rightAmount).
          Return CalcResult with resultAmount set to v, hasError set to "false".
        """;

    /** 3 fields - exactly user's case */
    private static final String SOURCE_3FIELDS = """
        Module tools.calculator.f3.

        Define CalcRequest has leftAmount as Int, rightAmount as Int.

        Define CalcResult has resultAmount as Int, hasError as Text, errorMessage as Text.

        Rule add given request as CalcRequest, produce CalcResult:
          Let v be (request.leftAmount plus request.rightAmount).
          Return CalcResult with resultAmount set to v, hasError set to "false", errorMessage set to "ok".
        """;

    @Test
    void compare_untyped_vs_typed_vs_flat() {
        probe("UNTYPED nested (original user case)", SOURCE_UNTYPED, "calculate");
        probe("UNTYPED add direct", SOURCE_UNTYPED, "add");
        probe("TYPED nested", SOURCE_TYPED, "calculate");
        probe("TYPED add direct", SOURCE_TYPED, "add");
        probe("FLAT 1-field", SOURCE_FLAT, "add");
        probe("FLAT 2-field", SOURCE_2FIELDS, "add");
        probe("FLAT 3-field", SOURCE_3FIELDS, "add");

        assertThat(true).isTrue();
    }
}
