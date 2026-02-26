package io.aster.llm.prompt;

import io.aster.llm.model.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptContext 单元测试
 *
 * 覆盖：三层消息组装、变量替换、修复上下文构建
 */
class PromptContextTest {

    @Test
    void toLlmRequest_三层消息应按序组装() {
        PromptContext ctx = new PromptContext()
            .systemPrompt("system msg")
            .developerPrompt("dev msg")
            .userPrompt("user msg")
            .model("gpt-4o")
            .temperature(0.5)
            .maxTokens(100);

        LlmRequest request = ctx.toLlmRequest();

        assertThat(request.model()).isEqualTo("gpt-4o");
        assertThat(request.temperature()).isEqualTo(0.5);
        assertThat(request.maxTokens()).isEqualTo(100);
        assertThat(request.stream()).isTrue();

        assertThat(request.messages()).hasSize(3);
        assertThat(request.messages().get(0).role()).isEqualTo("system");
        assertThat(request.messages().get(0).content()).isEqualTo("system msg");
        assertThat(request.messages().get(1).role()).isEqualTo("developer");
        assertThat(request.messages().get(1).content()).isEqualTo("dev msg");
        assertThat(request.messages().get(2).role()).isEqualTo("user");
        assertThat(request.messages().get(2).content()).isEqualTo("user msg");
    }

    @Test
    void toLlmRequest_省略空层应不生成消息() {
        PromptContext ctx = new PromptContext()
            .systemPrompt("system only")
            .userPrompt("user only")
            .model("test")
            .temperature(0.0)
            .maxTokens(10);

        LlmRequest request = ctx.toLlmRequest();

        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo("system");
        assertThat(request.messages().get(1).role()).isEqualTo("user");
    }

    @Test
    void withVariables_应替换模板变量() {
        PromptContext ctx = new PromptContext()
            .systemPrompt("Language: {locale}")
            .developerPrompt("Schema: {schema}")
            .userPrompt("Goal: {goal}")
            .model("m")
            .temperature(0.1)
            .maxTokens(50);

        PromptContext replaced = ctx.withVariables(Map.of(
            "locale", "zh-CN",
            "schema", "{\"type\":\"object\"}",
            "goal", "贷款审批策略"
        ));

        assertThat(replaced.getSystemPrompt()).isEqualTo("Language: zh-CN");
        assertThat(replaced.getDeveloperPrompt()).isEqualTo("Schema: {\"type\":\"object\"}");
        assertThat(replaced.getUserPrompt()).isEqualTo("Goal: 贷款审批策略");
    }

    @Test
    void forRepair_应保留System和Developer_追加历史() {
        PromptContext original = new PromptContext()
            .systemPrompt("system")
            .developerPrompt("developer")
            .userPrompt("original user prompt")
            .model("gpt-4o")
            .temperature(0.2)
            .maxTokens(2048);

        PromptContext repairCtx = original.forRepair(
            "Module invalid.",
            "修复以下错误：语法错误 at line 1"
        );

        LlmRequest request = repairCtx.toLlmRequest();

        // system + developer + original user + assistant(previous) + repair user
        assertThat(request.messages()).hasSize(5);
        assertThat(request.messages().get(0).role()).isEqualTo("system");
        assertThat(request.messages().get(0).content()).isEqualTo("system");
        assertThat(request.messages().get(1).role()).isEqualTo("developer");
        assertThat(request.messages().get(1).content()).isEqualTo("developer");
        assertThat(request.messages().get(2).role()).isEqualTo("user");
        assertThat(request.messages().get(2).content()).isEqualTo("original user prompt");
        assertThat(request.messages().get(3).role()).isEqualTo("assistant");
        assertThat(request.messages().get(3).content()).isEqualTo("Module invalid.");
        assertThat(request.messages().get(4).role()).isEqualTo("user");
        assertThat(request.messages().get(4).content()).contains("修复以下错误");

        // model/temperature/maxTokens 应继承
        assertThat(request.model()).isEqualTo("gpt-4o");
        assertThat(request.temperature()).isEqualTo(0.2);
    }

    @Test
    void forRepair_连续修复应累积历史() {
        PromptContext original = new PromptContext()
            .systemPrompt("sys")
            .userPrompt("generate policy")
            .model("m")
            .temperature(0.0)
            .maxTokens(10);

        // 第一次修复
        PromptContext repair1 = original.forRepair("attempt1", "fix error 1");
        // 第二次修复（基于第一次修复上下文）
        PromptContext repair2 = repair1.forRepair("attempt2", "fix error 2");

        LlmRequest request = repair2.toLlmRequest();

        // sys + user(generate) + assistant(attempt1) + user(fix1) + assistant(attempt2) + user(fix2)
        assertThat(request.messages()).hasSize(6);
        assertThat(request.messages().get(0).role()).isEqualTo("system");
        assertThat(request.messages().get(3).content()).isEqualTo("fix error 1");
        assertThat(request.messages().get(4).role()).isEqualTo("assistant");
        assertThat(request.messages().get(4).content()).isEqualTo("attempt2");
        assertThat(request.messages().get(5).content()).isEqualTo("fix error 2");
    }
}
