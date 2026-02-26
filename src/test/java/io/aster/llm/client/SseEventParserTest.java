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
        void parseFinishReason_stop应返回DONE() {
            String data = """
                {"choices":[{"delta":{},"finish_reason":"stop"}]}""";
            LlmStreamEvent event = parser.parseLine(data, "openai");

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
        void parseError_应返回ERROR() {
            String data = """
                {"type":"error","error":{"message":"Rate limit exceeded"}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(LlmStreamEvent.Type.ERROR);
            assertThat(event.error()).isEqualTo("Rate limit exceeded");
        }

        @Test
        void parseUnknownType_应返回null() {
            String data = """
                {"type":"message_start","message":{"id":"msg_123"}}""";
            LlmStreamEvent event = parser.parseLine(data, "anthropic");

            assertThat(event).isNull();
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
