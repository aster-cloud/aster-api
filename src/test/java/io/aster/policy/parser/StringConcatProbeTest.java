package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归探针：{@code +} 运算符在任一操作数为字符串时执行字符串拼接，而非把字符串
 * 强转为 Number（旧 bug 会抛 ClassCastException: String cannot be cast to Number）。
 *
 * 复现自生产报告的 preview.demo / greet 例子（WebSocket 预览端点）：
 *   Return "Hello, " + name + "!"
 *
 * 期望：返回 "Hello, John Smith!"。truffle Builtins.add 自 2026-06-06（v1.0.0+）
 * 即支持 dual-mode（任一操作数是字符串则拼接）；本测试锁定该行为不回归。
 */
class StringConcatProbeTest {

    private static final String GREET_SOURCE = """
        Module preview.demo.

        Rule greet given name as Text, produce Text:
          Return "Hello, " + name + "!".
        """;

    @Test
    void plusConcatenatesStringsInsteadOfCoercingToNumber() {
        Map<String, Object> context = Map.of("name", "John Smith");
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(GREET_SOURCE, context, "greet", "en-US");
        assertThat(r.result()).isEqualTo("Hello, John Smith!");
    }

    // 删除 aster-api 旧 int-only builtin 覆盖后，evaluate-source 改用 truffle 的
    // 权威语义。下面两条锁定该语义不再被本地覆盖回退（Codex 审查建议）。

    private static final String DIV_SOURCE = """
        Module preview.divprobe.

        Rule half given x as Int, produce:
          Return x / 2.
        """;

    @Test
    void divisionIsFloatingPointNotIntegerTruncation() {
        // 旧 int-only 覆盖会算成 7/2=3（截断）；truffle `/` 是浮点除法 → 3.5。
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            DIV_SOURCE, Map.of("x", 7), "half", "en-US");
        assertThat(((Number) r.result()).doubleValue()).isEqualTo(3.5);
    }

    private static final String EQ_SOURCE = """
        Module preview.eqprobe.

        Rule sameValue given a as Int, b as Int, produce:
          Return a equals to b.
        """;

    @Test
    void integerArithmeticStillExact() {
        // 整数路径不能因删除而回归：truffle add 对 int+int 仍返回精确整数。
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            EQ_SOURCE, Map.of("a", 5, "b", 5), "sameValue", "en-US");
        assertThat(r.result()).isEqualTo(true);
    }
}
