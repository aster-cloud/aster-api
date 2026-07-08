package io.aster.llm.model;

/**
 * 非流式 LLM 调用结果（issue #185）：内容 + 真实 token 用量。
 * 用量为 null 表示 provider 未返回 usage（不精确回填，cloud 占位仍在）。
 */
public record LlmChatResult(String content, LlmUsage usage) {

    public static LlmChatResult of(String content) {
        return new LlmChatResult(content, null);
    }
}
