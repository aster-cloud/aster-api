package io.aster.llm.model;

/**
 * 一次 LLM 调用的 token 用量（issue #185）。用于把 provider 返回的真实 token 从执行层
 * （VertxLlmClient）带回业务层，再回填给 cloud（精确成本计量）。
 *
 * <p>SSE 多 attempt（generate repair 重试）会多次产生 usage，业务层负责累加（见 LlmProxyService）。
 */
public record LlmUsage(int promptTokens, int completionTokens) {

    public static final LlmUsage ZERO = new LlmUsage(0, 0);

    /** 跨 attempt（repair 多次 provider 调用）累加。 */
    public LlmUsage plus(LlmUsage other) {
        if (other == null) {
            return this;
        }
        return new LlmUsage(this.promptTokens + other.promptTokens,
            this.completionTokens + other.completionTokens);
    }

    /**
     * 单个 attempt 内逐分量取大（issue #185）。用于 Anthropic 流式：message_delta 的
     * {@code output_tokens} 是<b>累计绝对值</b>（一个流多个 delta 递增），须取 latest/max 而非累加，
     * 否则 overcount。OpenAI 每 attempt 只有一个 final usage 帧，max 等价于该值。
     */
    public LlmUsage max(LlmUsage other) {
        if (other == null) {
            return this;
        }
        return new LlmUsage(Math.max(this.promptTokens, other.promptTokens),
            Math.max(this.completionTokens, other.completionTokens));
    }

    public boolean hasTokens() {
        return promptTokens > 0 || completionTokens > 0;
    }
}
