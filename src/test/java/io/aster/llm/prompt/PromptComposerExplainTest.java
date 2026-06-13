package io.aster.llm.prompt;

import io.aster.llm.api.dto.ExplainRequest;
import io.aster.llm.config.LlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证策略解释 prompt 的硬要求：
 *  - 必须把执行 trace 作为数据拼入，并明确要求引用其中真实数值、不得留空；
 *  - 必须要求描述全部字段（含未用字段）、逐步走查、给出可对客户/监管解释的最终理由；
 *  - 无 trace 时不得编造具体数值。
 *
 * 这些要求直接对应线上反馈「AI 解释字段/数字缺失、不友好」的根因修复，需有测试钉住防回归。
 */
class PromptComposerExplainTest {

    private PromptComposer composer;

    @BeforeEach
    void setUp() throws Exception {
        composer = new PromptComposer();
        // buildExplainContext 仅依赖 config.model()/maxTokens()，用动态代理桩 LlmConfig。
        LlmConfig config = (LlmConfig) Proxy.newProxyInstance(
            LlmConfig.class.getClassLoader(),
            new Class<?>[] {LlmConfig.class},
            stubHandler(Map.of("model", "gpt-5.2", "maxTokens", 2048)));
        setField(composer, "config", config);
    }

    @Test
    void explain_with_trace_requires_citing_real_values_and_no_blanks() {
        String source = "Module credit.approval.\nRule decide given a as Applicant, produce Text:\n  Return \"x\".";
        Object trace = Map.of(
            "moduleName", "credit.approval",
            "finalResult", "Declined — credit score below threshold",
            "steps", java.util.List.of(Map.of(
                "sequence", 1, "expression", "creditScore 561 >= 600", "result", "false", "matched", false)));

        PromptContext ctx = composer.buildExplainContext("t1", new ExplainRequest(source, "zh-CN", trace));

        String system = ctx.getSystemPrompt();
        String user = ctx.getUserPrompt();

        // 系统提示必须包含「引用真实数值 + 禁止留空 + 描述所有字段 + 面向合规」的约束。
        assertThat(system)
            .contains("cite the concrete input values")
            .contains("empty cells are not acceptable")
            .contains("EVERY field")
            .contains("risk and compliance");
        // trace 必须作为数据拼入 user prompt，且要求引用其中数值。
        assertThat(user)
            .contains("Execution trace")
            .contains("561")
            .contains("do not output any blank");
        // 输出语言指令存在。
        assertThat(user).contains("中文");
    }

    @Test
    void explain_without_trace_forbids_fabricating_values() {
        String source = "Module m.\nRule r given a as A, produce Text:\n  Return \"y\".";

        PromptContext ctx = composer.buildExplainContext("t1", new ExplainRequest(source, "en-US", null));

        String user = ctx.getUserPrompt();
        assertThat(user)
            .doesNotContain("Execution trace")
            .contains("do not fabricate specific applicant values");
        assertThat(user).contains("English");
    }

    // ── helpers ──

    private static InvocationHandler stubHandler(Map<String, Object> values) {
        return (proxy, method, args) -> {
            Object v = values.get(method.getName());
            if (v != null) return v;
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0.0;
            return null;
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
