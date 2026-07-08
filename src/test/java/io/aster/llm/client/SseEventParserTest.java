package io.aster.llm.client;

import io.aster.llm.model.LlmStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SseEventParser 单元测试
 *
 * 覆盖：OpenAI/Anthropic 格式解析、[DONE] 终止、分片场景、异常数据
 */
class SseEventParserTest {

    private SseEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new SseEventParser();
    }

    @Nested
    class OpenAiFormat {

        @Test
        void parseDelta_应返回增量文本() {
            String data = """
                {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DELTA);
            assertThat(event.delta()).isEqualTo("Hello");
        }

        @Test
        void parseFinishReason_stop不再直接DONE_等usage帧() {
            // issue #185：开启 stream_options.include_usage 后，finish_reason=stop 帧之后还有 usage 帧。
            // 若在 stop 就 DONE 会提前结束流、拿不到 token。stop 帧现在返回 null（继续等 usage + [DONE]）。
            String data = """
                {"choices":[{"delta":{},"finish_reason":"stop"}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");
            assertThat(event).isNull();
        }

        @Test
        void parseUsage帧_返回USAGE事件带真实token() {
            // #185：choices 空 + 根部 usage → USAGE 事件
            String data = """
                {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":45}}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.USAGE);
            assertThat(event.usage().promptTokens()).isEqualTo(120);
            assertThat(event.usage().completionTokens()).isEqualTo(45);
        }

        @Test
        void parseDoneMarker_返回DONE() {
            LlmStreamEvent event = parser.parseLine("[DONE]", "openai");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DONE);
        }

        @Test
        void parseDelta_空content应返回null() {
            String data = """
                {"choices":[{"delta":{},"finish_reason":null}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

            assertThat(event).isNull();
        }

        @Test
        void parseEmptyChoices_应返回null() {
            String data = """
                {"choices":[]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

            assertThat(event).isNull();
        }

        @Test
        void parseDelta_包含中文() {
            String data = """
                {"choices":[{"delta":{"content":"你好世界"},"finish_reason":null}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

            assertThat(event).isNotNull();
            assertThat(event.delta()).isEqualTo("你好世界");
        }

        @Test
        void parseDelta_包含换行符() {
            String data = """
                {"choices":[{"delta":{"content":"line1\\nline2"},"finish_reason":null}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

            assertThat(event).isNotNull();
            assertThat(event.delta()).contains("line1");
        }
    }

    @Nested
    class AnthropicFormat {

        @Test
        void parseContentBlockDelta_应返回增量文本() {
            String data = """
                {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DELTA);
            assertThat(event.delta()).isEqualTo("Hello");
        }

        @Test
        void parseMessageStop_应返回DONE() {
            String data = """
                {"type":"message_stop"}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DONE);
        }

        @Test
        void parseError_应返回ERROR_且脱敏不回显provider_message() {
            // 红队硬化（Phase 4）：provider 的 error.message 可能含 key 前缀/鉴权诊断，不回显前端。
            String data = """
                {"type":"error","error":{"message":"Rate limit exceeded; key sk-ant-xxx invalid"}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.ERROR);
            // 泛化文案，绝不包含 provider 原始 message
            assertThat(event.error()).isEqualTo("LLM 流式响应错误");
            assertThat(event.error()).doesNotContain("sk-ant").doesNotContain("Rate limit");
        }

        @Test
        void parseUnknownType_应返回null() {
            String data = """
                {"type":"message_start","message":{"id":"msg_123"}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNull();
        }

        @Test
        void parseMessageStart_带input_tokens返回USAGE() {
            // #185：Anthropic message_start 携带 input_tokens
            String data = """
                {"type":"message_start","message":{"usage":{"input_tokens":88,"output_tokens":1}}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.USAGE);
            assertThat(event.usage().promptTokens()).isEqualTo(88);
            assertThat(event.usage().completionTokens()).isEqualTo(0);
        }

        @Test
        void parseMessageDelta_带output_tokens返回USAGE() {
            // #185：Anthropic message_delta 携带累计 output_tokens
            String data = """
                {"type":"message_delta","delta":{},"usage":{"output_tokens":52}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.USAGE);
            assertThat(event.usage().promptTokens()).isEqualTo(0);
            assertThat(event.usage().completionTokens()).isEqualTo(52);
        }
    }

    @Nested
    class CommonCases {

        @Test
        void parseDone_大写应识别() {
            LlmStreamEvent event = parser.parseLine("[DONE]", "openai");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DONE);
        }

        @Test
        void parseDone_小写应识别() {
            LlmStreamEvent event = parser.parseLine("[done]", "openai");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DONE);
        }

        @Test
        void parseDone_带空格应识别() {
            LlmStreamEvent event = parser.parseLine("  [DONE]  ", "openai");
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.DONE);
        }

        @Test
        void parseNull_应返回null() {
            assertThat(parser.parseLine(null, "openai")).isNull();
        }

        @Test
        void parseEmpty_应返回null() {
            assertThat(parser.parseLine("", "openai")).isNull();
            assertThat(parser.parseLine("   ", "openai")).isNull();
        }

        @Test
        void parseInvalidJson_应返回null() {
            assertThat(parser.parseLine("not json", "openai")).isNull();
            assertThat(parser.parseLine("{broken", "openai")).isNull();
        }

        @Test
        void parseDefaultProvider_应按OpenAi处理() {
            String data = """
                {"choices":[{"delta":{"content":"test"},"finish_reason":null}]}""";
            LlmStreamEvent event = parser.parseLine(data, "custom");

            assertThat(event).isNotNull();
            assertThat(event.delta()).isEqualTo("test");
        }
    }
}
