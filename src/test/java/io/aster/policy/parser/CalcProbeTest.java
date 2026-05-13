package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: 用户函数名与 builtin 同名时的优先级。
 *
 * 历史 bug：用户定义 `Rule add given request, produce CalcResult: ...` 时，
 * Loader.buildExpr 看到 `Call(Name("add"), ...)` 检查 `Builtins.has("add")=true`
 * 后直接走 BuiltinCallNode（算术 add(a,b)），arity 失配后 fallback 路径在
 * DSL 重写时被静默吞掉，最终返回 Integer(0)。
 *
 * 修复：Loader 记录用户定义函数名集合 `userFunctionNames`，buildExpr 在
 * 分发 Call 时**先查**该集合 —— 用户函数屏蔽同名 builtin。
 *
 * 此测试覆盖：
 *   - UNTYPED nested: calculate → add（用户的 add 屏蔽 builtin add）
 *   - TYPED nested: 同上 + 显式字段类型
 *   - FLAT: 单 Rule 路径，确保未引入新回归
 *
 * 输入 1000 + 1000 期望结果 2000；任一分支返回 0 即说明 bug 复发。
 */
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

    private static Object eval(String source, String entry) {
        Map<String, Object> request = Map.of("leftAmount", 1000, "rightAmount", 1000);
        Map<String, Object> context = Map.of("request", request);
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(source, context, entry, "en-US");
        return r.result();
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

    /**
     * 用户函数 `add` 必须屏蔽 builtin 算术 `add`。
     * 此前 Loader.buildExpr 优先走 BuiltinCallNode，导致 user 的 add 调用
     * 被算术 add 拦截，1 个 Map 参数 + 期待 2 个 Int → 静默返回 0。
     */
    @Test
    void user_add_shadows_builtin_add_untyped() {
        Object r = eval(SOURCE_UNTYPED, "calculate");
        assertThat(r).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) r;
        assertThat(m).containsEntry("resultAmount", 2000);
    }

    @Test
    void user_add_shadows_builtin_add_typed() {
        Object r = eval(SOURCE_TYPED, "calculate");
        assertThat(r).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) r;
        assertThat(m).containsEntry("resultAmount", 2000);
    }

    @Test
    void direct_call_of_user_add_works() {
        Object r = eval(SOURCE_TYPED, "add");
        assertThat(r).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) r;
        assertThat(m).containsEntry("resultAmount", 2000);
    }

    /** Sanity check：单 Rule 路径不能因为本次修复回归。 */
    @Test
    void single_rule_still_works() {
        for (var src : new String[]{SOURCE_FLAT, SOURCE_2FIELDS, SOURCE_3FIELDS}) {
            Object r = eval(src, "add");
            assertThat(r).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) r;
            assertThat(m).containsEntry("resultAmount", 2000);
        }
    }
}
