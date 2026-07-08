package io.aster.llm.model;

/**
 * LLM 流式响应事件
 *
 * @param type  事件类型：delta / done / error / usage
 * @param delta 增量文本（仅 delta 类型）
 * @param error 错误信息（仅 error 类型）
 * @param usage token 用量（仅 usage 类型，issue #185）
 */
public record LlmStreamEvent(
    Type type,
    String delta,
    String error,
    LlmUsage usage
) {
    public enum Type {
        DELTA,
        DONE,
        ERROR,
        /** SSE 末帧携带的 token 用量（issue #185：OpenAI stream_options.include_usage / Anthropic message_delta）。 */
        USAGE
    }

    public static LlmStreamEvent delta(String text) {
        return new LlmStreamEvent(Type.DELTA, text, null, null);
    }

    public static LlmStreamEvent done() {
        return new LlmStreamEvent(Type.DONE, null, null, null);
    }

    public static LlmStreamEvent error(String message) {
        return new LlmStreamEvent(Type.ERROR, null, message, null);
    }

    public static LlmStreamEvent usage(LlmUsage usage) {
        return new LlmStreamEvent(Type.USAGE, null, null, usage);
    }
}
