package io.aster.llm.client;

import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * LLM API 客户端接口
 *
 * 抽象 LLM 提供商的 HTTP 交互，支持流式和非流式模式。
 *
 * <p>Phase 2：调用凭证与目标由 per-request {@link LlmRuntimeOptions} 携带（provider/baseUrl/apiKey），
 * 使同一 client 既能走平台 key 也能走用户 BYOK key，而不在执行层判断来源。
 */
public interface LlmClient {

    /**
     * 流式聊天请求
     *
     * 发送请求到 LLM API 并以 SSE 事件流返回响应。
     *
     * @param request LLM 请求
     * @param options 本次调用的运行时凭证与目标（provider/baseUrl/apiKey/source）
     * @return 流式事件
     */
    Multi<LlmStreamEvent> streamChat(LlmRequest request, LlmRuntimeOptions options);

    /**
     * 非流式聊天请求（低延迟场景如代码补全）
     *
     * @param request LLM 请求（stream=false）
     * @param options 本次调用的运行时凭证与目标
     * @return 完整响应文本
     */
    Uni<String> chat(LlmRequest request, LlmRuntimeOptions options);
}
