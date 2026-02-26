package io.aster.llm.prompt;

import io.aster.llm.model.LlmRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prompt 上下文
 *
 * 封装三层 Prompt（System / Developer / User）的组装结果，
 * 并提供变量替换和转换为 LlmRequest 的能力。
 */
public class PromptContext {

    private String systemPrompt;
    private String developerPrompt;
    private String userPrompt;
    private String model;
    private double temperature;
    private int maxTokens;

    /** 历史消息（修复场景：上一轮 assistant 输出 + 错误反馈） */
    private final List<LlmRequest.Message> history = new ArrayList<>();

    public PromptContext systemPrompt(String prompt) {
        this.systemPrompt = prompt;
        return this;
    }

    public PromptContext developerPrompt(String prompt) {
        this.developerPrompt = prompt;
        return this;
    }

    public PromptContext userPrompt(String prompt) {
        this.userPrompt = prompt;
        return this;
    }

    public PromptContext model(String model) {
        this.model = model;
        return this;
    }

    public PromptContext temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    public PromptContext maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    public PromptContext addHistory(LlmRequest.Message message) {
        this.history.add(message);
        return this;
    }

    /**
     * 在已有 Prompt 中替换变量
     *
     * @param variables 变量映射 {key -> value}
     * @return 替换后的新 PromptContext
     */
    public PromptContext withVariables(Map<String, String> variables) {
        PromptContext ctx = new PromptContext()
            .systemPrompt(replaceVars(this.systemPrompt, variables))
            .developerPrompt(replaceVars(this.developerPrompt, variables))
            .userPrompt(replaceVars(this.userPrompt, variables))
            .model(this.model)
            .temperature(this.temperature)
            .maxTokens(this.maxTokens);
        ctx.history.addAll(this.history);
        return ctx;
    }

    /**
     * 转换为 LlmRequest
     */
    public LlmRequest toLlmRequest() {
        List<LlmRequest.Message> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(LlmRequest.Message.system(systemPrompt));
        }
        if (developerPrompt != null && !developerPrompt.isBlank()) {
            messages.add(LlmRequest.Message.developer(developerPrompt));
        }

        // 插入历史消息（修复场景）
        messages.addAll(history);

        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(LlmRequest.Message.user(userPrompt));
        }

        return new LlmRequest(model, messages, temperature, maxTokens, true);
    }

    /**
     * 创建修复上下文：保留 System + Developer，将上次输出作为 assistant 历史，
     * 新增修复指令作为 user 消息
     */
    public PromptContext forRepair(String previousOutput, String repairPrompt) {
        PromptContext repairCtx = new PromptContext()
            .systemPrompt(this.systemPrompt)
            .developerPrompt(this.developerPrompt)
            .model(this.model)
            .temperature(this.temperature)
            .maxTokens(this.maxTokens);

        // 保留已有历史
        repairCtx.history.addAll(this.history);
        // 追加上次输出 + 原始 user 消息
        if (this.userPrompt != null) {
            repairCtx.history.add(LlmRequest.Message.user(this.userPrompt));
        }
        repairCtx.history.add(LlmRequest.Message.assistant(previousOutput));

        // 修复指令
        repairCtx.userPrompt(repairPrompt);

        return repairCtx;
    }

    private static String replaceVars(String template, Map<String, String> variables) {
        if (template == null) return null;
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public String getDeveloperPrompt() { return developerPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getModel() { return model; }
}
