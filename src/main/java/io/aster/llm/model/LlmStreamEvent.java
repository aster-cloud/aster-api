package io.aster.llm.model;

/**
 * LLM 流式响应事件
 *
 * @param type  事件类型：delta / done / error
 * @param delta 增量文本（仅 delta 类型）
 * @param error 错误信息（仅 error 类型）
 */
public record LlmStreamEvent(
    Type type,
    String delta,
    String error
) {
    public enum Type {
        DELTA,
        DONE,
        ERROR
    }

    public static LlmStreamEvent delta(String text) {
        return new LlmStreamEvent(Type.DELTA, text, null);
    }

    public static LlmStreamEvent done() {
        return new LlmStreamEvent(Type.DONE, null, null);
    }

    public static LlmStreamEvent error(String message) {
        return new LlmStreamEvent(Type.ERROR, null, message);
    }
}
