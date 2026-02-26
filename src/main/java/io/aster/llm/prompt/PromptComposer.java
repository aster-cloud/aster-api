package io.aster.llm.prompt;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.ExplainRequest;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 组合器
 *
 * 三层 Prompt 组装：System（固定语法规则） → Developer（场景约束） → User（动态目标）。
 * 确保 System prompt 不可被用户输入覆盖（防 Prompt 注入）。
 */
@ApplicationScoped
public class PromptComposer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PromptTemplateRegistry templates;

    @Inject
    LlmConfig config;

    /**
     * 构建策略生成上下文
     */
    public PromptContext buildGenerateContext(String tenantId, GeneratePolicyRequest req) {
        String locale = req.getLocaleOrDefault();
        String model = req.model() != null ? req.model() : config.model();

        // System: aster-lang 语法规则（固定）
        String systemPrompt = templates.load("system", "system_base", locale);

        // Developer: 策略生成约束
        String developerPrompt = templates.load("developer", "policy_gen", locale);

        // User: 动态需求
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("goal", req.goal());
        vars.put("locale", locale);
        vars.put("existing_source", req.existingSource() != null ? req.existingSource() : "");
        vars.put("schema_json", serializeSchema(req.schema()));

        String userPrompt = buildUserPrompt(req);

        return new PromptContext()
            .systemPrompt(systemPrompt)
            .developerPrompt(developerPrompt)
            .userPrompt(userPrompt)
            .model(model)
            .temperature(config.temperature())
            .maxTokens(config.maxTokens());
    }

    /**
     * 构建修复上下文（编译校验失败后重试）
     */
    public PromptContext buildRepairContext(
        PromptContext originalCtx,
        String previousOutput,
        ValidationResult validationResult,
        String locale
    ) {
        String repairTemplate = templates.load("developer", "policy_repair", locale);

        String repairPrompt = repairTemplate
            .replace("{errors}", validationResult.errorsAsString())
            .replace("{previous_output}", previousOutput);

        return originalCtx.forRepair(previousOutput, repairPrompt);
    }

    /**
     * 构建策略解释上下文
     */
    public PromptContext buildExplainContext(String tenantId, ExplainRequest req) {
        String locale = req.getLocaleOrDefault();

        String systemPrompt = "You are an aster-lang policy expert. "
            + "Your task is to explain policy code logic clearly and concisely. "
            + "Reply in the language requested by the user.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("请解释以下 aster-lang 策略代码的逻辑：\n\n");
        userPrompt.append("```\n").append(req.source()).append("\n```\n\n");
        if (req.traceData() != null) {
            userPrompt.append("执行追踪数据：\n");
            userPrompt.append(serializeSchema(req.traceData())).append("\n\n");
        }
        userPrompt.append("请用").append(localeToLanguageName(locale)).append("回答。");

        return new PromptContext()
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt.toString())
            .model(config.model())
            .temperature(0.3)
            .maxTokens(config.maxTokens());
    }

    /**
     * 构建代码补全上下文
     */
    public PromptContext buildCompleteContext(String tenantId, CompleteRequest req) {
        String locale = req.getLocaleOrDefault();
        String model = req.model() != null ? req.model() : config.model();

        String systemPrompt = templates.load("system", "system_base", locale);

        String userPrompt = "请续写以下 aster-lang 策略代码（只输出续写部分，不要重复已有内容）：\n\n" + req.prefix();

        return new PromptContext()
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .model(model)
            .temperature(0.1) // 补全场景低温度
            .maxTokens(256);  // 补全不需要长输出
    }

    private String buildUserPrompt(GeneratePolicyRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("需求：").append(req.goal()).append("\n");

        if (req.existingSource() != null && !req.existingSource().isBlank()) {
            sb.append("\n现有策略代码：\n```\n").append(req.existingSource()).append("\n```\n");
            sb.append("请在此基础上修改/优化。\n");
        }

        if (req.schema() != null) {
            sb.append("\n输入参数 Schema：\n```json\n").append(serializeSchema(req.schema())).append("\n```\n");
        }

        sb.append("\n目标语言：").append(req.getLocaleOrDefault());
        sb.append("\n\n只输出 aster-lang 策略源代码，不要输出解释。");

        return sb.toString();
    }

    private String serializeSchema(Object schema) {
        if (schema == null) return "";
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            return String.valueOf(schema);
        }
    }

    private String localeToLanguageName(String locale) {
        if (locale == null) return "中文";
        String lower = locale.toLowerCase();
        if (lower.startsWith("en")) return "English";
        if (lower.startsWith("de")) return "Deutsch";
        return "中文";
    }
}
