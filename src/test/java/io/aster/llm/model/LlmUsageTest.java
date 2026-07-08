package io.aster.llm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmUsage 累加语义（issue #185）：SSE 多帧/多 attempt token 累加。
 */
class LlmUsageTest {

    @Test
    void plus_累加两笔token() {
        LlmUsage a = new LlmUsage(100, 50);
        LlmUsage b = new LlmUsage(20, 30);
        LlmUsage sum = a.plus(b);
        assertThat(sum.promptTokens()).isEqualTo(120);
        assertThat(sum.completionTokens()).isEqualTo(80);
    }

    @Test
    void plus_null安全() {
        LlmUsage a = new LlmUsage(10, 5);
        assertThat(a.plus(null)).isEqualTo(a);
    }

    @Test
    void anthropic流式累加_input从start_output从delta() {
        // 模拟 Anthropic 流式：message_start(input) + message_delta(output) 累加
        LlmUsage acc = LlmUsage.ZERO
            .plus(new LlmUsage(88, 0))   // message_start input_tokens
            .plus(new LlmUsage(0, 52));  // message_delta output_tokens
        assertThat(acc.promptTokens()).isEqualTo(88);
        assertThat(acc.completionTokens()).isEqualTo(52);
    }

    @Test
    void max_逐分量取大_Anthropic累计output不overcount() {
        // Anthropic message_delta output_tokens 是累计绝对值：10 → 15。attempt 内取 max=15（非 25）。
        LlmUsage acc = LlmUsage.ZERO
            .max(new LlmUsage(88, 0))    // message_start input=88
            .max(new LlmUsage(0, 10))    // message_delta output 累计=10
            .max(new LlmUsage(0, 15));   // message_delta output 累计=15
        assertThat(acc.promptTokens()).isEqualTo(88);
        assertThat(acc.completionTokens()).isEqualTo(15); // ★不是 25
    }

    @Test
    void max_然后plus_模拟repair两attempt() {
        // 每 attempt 内 max，跨 attempt sum。两 attempt 各 (88,15) → 全局 (176,30)。
        LlmUsage a1 = LlmUsage.ZERO.max(new LlmUsage(88, 0)).max(new LlmUsage(0, 15));
        LlmUsage a2 = LlmUsage.ZERO.max(new LlmUsage(88, 0)).max(new LlmUsage(0, 15));
        LlmUsage global = LlmUsage.ZERO.plus(a1).plus(a2);
        assertThat(global.promptTokens()).isEqualTo(176);
        assertThat(global.completionTokens()).isEqualTo(30);
    }

    @Test
    void max_null安全() {
        assertThat(new LlmUsage(5, 3).max(null)).isEqualTo(new LlmUsage(5, 3));
    }

    @Test
    void hasTokens_零为false() {
        assertThat(LlmUsage.ZERO.hasTokens()).isFalse();
        assertThat(new LlmUsage(1, 0).hasTokens()).isTrue();
        assertThat(new LlmUsage(0, 1).hasTokens()).isTrue();
    }
}
