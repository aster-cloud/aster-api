package io.aster.llm.service;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.ExplainRequest;
import io.aster.llm.api.dto.GeneratePolicyEvent;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.ValidationResult;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.prompt.PromptContext;
import io.aster.llm.tenant.TenantLlmKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 代理服务
 *
 * 核心编排层：Prompt 组装 → LLM 调用 → SSE 流式 → 编译校验闭环。
 * 校验失败时自动重试（最多 maxAttempts 次），使用修复 Prompt 引导 LLM 修正。
 */
@ApplicationScoped
public class LlmProxyService {

    private static final Logger LOG = Logger.getLogger(LlmProxyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PromptComposer promptComposer;

    @Inject
    LlmClient llmClient;

    @Inject
    PolicyCompileValidator validator;

    @Inject
    LlmConfig config;

    @Inject
    TenantLlmKeyProvider keyProvider;

    /**
     * 流式策略生成（含编译校验闭环）
     *
     * 流程：
     * 1. 组装 Prompt → 调用 LLM → 流式输出 delta 事件
     * 2. 流结束后执行编译校验
     * 3. 校验通过 → 输出 final 事件
     * 4. 校验失败 → 输出 validation_error → 构建修复 Prompt → 重新生成
     * 5. 最多重试 maxAttempts 次
     */
    public Multi<String> streamGenerate(String tenantId, GeneratePolicyRequest req) {
        String apiKey = keyProvider.getApiKey(tenantId, config.provider());

        if (apiKey == null || apiKey.isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        return Multi.createFrom().emitter(emitter -> {
            PromptContext ctx = promptComposer.buildGenerateContext(tenantId, req);
            int maxAttempts = config.validation().maxAttempts();
            AtomicInteger attempt = new AtomicInteger(1);
            AtomicReference<PromptContext> currentCtx = new AtomicReference<>(ctx);

            executeGenerateAttempt(currentCtx.get(), apiKey, req.getLocaleOrDefault(),
                attempt, maxAttempts, currentCtx, emitter);
        });
    }

    private void executeGenerateAttempt(
        PromptContext ctx,
        String apiKey,
        String locale,
        AtomicInteger attempt,
        int maxAttempts,
        AtomicReference<PromptContext> currentCtx,
        io.smallrye.mutiny.subscription.MultiEmitter<? super String> emitter
    ) {
        StringBuilder fullSource = new StringBuilder();
        int currentAttempt = attempt.get();

        LOG.infof("LLM 生成尝试 %d/%d", currentAttempt, maxAttempts);

        llmClient.streamChat(ctx.toLlmRequest(), apiKey)
            .subscribe().with(
                event -> {
                    if (event.type() == LlmStreamEvent.Type.DELTA && event.delta() != null) {
                        fullSource.append(event.delta());
                        emitter.emit(toJson(GeneratePolicyEvent.delta(event.delta())));
                    } else if (event.type() == LlmStreamEvent.Type.ERROR) {
                        emitter.emit(toJson(GeneratePolicyEvent.error(event.error())));
                        emitter.complete();
                    }
                },
                throwable -> {
                    LOG.errorf(throwable, "LLM 流式调用异常");
                    emitter.emit(toJson(GeneratePolicyEvent.error("LLM 调用失败: " + throwable.getMessage())));
                    emitter.complete();
                },
                () -> {
                    // 流结束，执行编译校验
                    String source = fullSource.toString().trim();
                    if (source.isEmpty()) {
                        emitter.emit(toJson(GeneratePolicyEvent.error("LLM 未生成内容")));
                        emitter.complete();
                        return;
                    }

                    ValidationResult result = validator.validate(source, locale);

                    if (result.ok()) {
                        // 校验通过，输出清理后的最终代码
                        String cleanedSource = validator.cleanLlmOutput(source);
                        emitter.emit(toJson(GeneratePolicyEvent.finalResult(cleanedSource, true)));
                        emitter.complete();
                    } else if (currentAttempt < maxAttempts) {
                        // 校验失败，尝试修复
                        LOG.warnf("编译校验失败 (尝试 %d/%d): %s",
                            currentAttempt, maxAttempts, result.errorsAsString());

                        emitter.emit(toJson(GeneratePolicyEvent.validationError(result.errorsAsString())));

                        // 构建修复 Prompt
                        PromptContext repairCtx = promptComposer.buildRepairContext(
                            currentCtx.get(), source, result, locale);
                        currentCtx.set(repairCtx);
                        int nextAttempt = attempt.incrementAndGet();

                        // 通知前端修复开始
                        emitter.emit(toJson(GeneratePolicyEvent.repairStart(nextAttempt, maxAttempts)));

                        // 递归重试
                        executeGenerateAttempt(repairCtx, apiKey, locale,
                            attempt, maxAttempts, currentCtx, emitter);
                    } else {
                        // 超出重试次数
                        LOG.errorf("编译校验最终失败 (%d 次尝试): %s",
                            maxAttempts, result.errorsAsString());
                        emitter.emit(toJson(GeneratePolicyEvent.validationError(result.errorsAsString())));
                        // 仍然输出最后一次的代码，让用户手动修复
                        String cleanedSource = validator.cleanLlmOutput(source);
                        emitter.emit(toJson(GeneratePolicyEvent.finalResult(cleanedSource, false)));
                        emitter.complete();
                    }
                }
            );
    }

    /**
     * 流式策略解释（直接透传 LLM 输出，无需编译校验）
     */
    public Multi<String> streamExplain(String tenantId, ExplainRequest req) {
        String apiKey = keyProvider.getApiKey(tenantId, config.provider());

        if (apiKey == null || apiKey.isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        PromptContext ctx = promptComposer.buildExplainContext(tenantId, req);

        return llmClient.streamChat(ctx.toLlmRequest(), apiKey)
            .filter(event -> event.type() == LlmStreamEvent.Type.DELTA && event.delta() != null)
            .map(event -> event.delta());
    }

    /**
     * 流式策略优化建议（直接透传 LLM 输出，无需编译校验）
     */
    public Multi<String> streamSuggest(String tenantId, SuggestRequest req) {
        String apiKey = keyProvider.getApiKey(tenantId, config.provider());

        if (apiKey == null || apiKey.isBlank()) {
            return Multi.createFrom().item(toJson(GeneratePolicyEvent.error("未配置 LLM API Key")));
        }

        PromptContext ctx = promptComposer.buildSuggestContext(tenantId, req);

        return llmClient.streamChat(ctx.toLlmRequest(), apiKey)
            .filter(event -> event.type() == LlmStreamEvent.Type.DELTA && event.delta() != null)
            .map(event -> event.delta());
    }

    /**
     * 代码补全（非流式，低延迟）
     */
    public Uni<CompleteResponse> complete(String tenantId, CompleteRequest req) {
        String apiKey = keyProvider.getApiKey(tenantId, config.provider());

        if (apiKey == null || apiKey.isBlank()) {
            return Uni.createFrom().item(new CompleteResponse("", config.model()));
        }

        PromptContext ctx = promptComposer.buildCompleteContext(tenantId, req);
        String model = req.model() != null ? req.model() : config.model();

        return llmClient.chat(ctx.toLlmRequest(), apiKey)
            .map(content -> new CompleteResponse(content, model))
            .onFailure().recoverWithItem(throwable -> {
                LOG.errorf(throwable, "代码补全失败");
                return new CompleteResponse("", model);
            });
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"序列化失败\"}";
        }
    }
}
