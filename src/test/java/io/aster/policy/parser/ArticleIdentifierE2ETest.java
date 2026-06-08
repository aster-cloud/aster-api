package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Canonicalizer 冠词移除收窄后，名为 a 的标识符不再被吞（端到端 Truffle 执行）。 */
class ArticleIdentifierE2ETest {

    // 最初触发 bug 的场景：三参数 a/b/c（a 是英文冠词）+ 逻辑运算符
    private static final String SOURCE = """
        Module article.identifier.

        Rule mixed given a, b, c, produce Bool:
          Return a equals to 1 or b equals to 2 and c equals to 3.
        """;

    // a 作 let 绑定名（后跟 be），此前被冠词移除吞掉
    private static final String LET_SOURCE = """
        Module article.letbinding.

        Rule compute given x, produce Int:
          Let a be x plus 1.
          Return a times 2.
        """;

    @Test
    void identifierA_notSwallowed_andExecutes() {
        // a==1 → true（不再因 a 被吞而解析失败）
        ExecutionResult r1 = DynamicCnlExecutor.executeWithContext(
            SOURCE, Map.of("a", 1, "b", 0, "c", 0), "mixed", "en-US", null, false);
        assertThat(r1.result()).isEqualTo(true);

        // a==0, b==2, c==3 → (b==2 and c==3) → true
        ExecutionResult r2 = DynamicCnlExecutor.executeWithContext(
            SOURCE, Map.of("a", 0, "b", 2, "c", 3), "mixed", "en-US", null, false);
        assertThat(r2.result()).isEqualTo(true);

        // 全不满足 → false
        ExecutionResult r3 = DynamicCnlExecutor.executeWithContext(
            SOURCE, Map.of("a", 0, "b", 2, "c", 0), "mixed", "en-US", null, false);
        assertThat(r3.result()).isEqualTo(false);
    }

    @Test
    void identifierA_atLineEnd_inLetBinding_executes() {
        // Let a be x plus 1（a 是 let 绑定名，后跟声明关键字 be；后续 `a times 2` 引用它）
        // x=5 → a=6 → a times 2 = 12
        ExecutionResult r = DynamicCnlExecutor.executeWithContext(
            LET_SOURCE, Map.of("x", 5), "compute", "en-US", null, false);
        assertThat(r.result()).isEqualTo(12);
    }
}
