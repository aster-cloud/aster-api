package io.aster.llm.client;

import io.aster.llm.model.LlmStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * SSE 事件解析器
 *
 * 解析 LLM API 返回的 Server-Sent Events 数据，
 * 支持 OpenAI 和 Anthropic 两种格式。
 *
 * SSE 帧格式：
 * data: {"choices":[{"delta":{"content":"text"}}]}
 * data: [DONE]
 */
@ApplicationScoped
public class SseEventParser {

    private static final Logger LOG = Logger.getLogger(SseEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析单个 SSE data 行
     *
     * @param dataLine SSE data: 之后的内容（已去掉前缀）
     * @param provider 提供商标识
     * @return 解析后的事件，或 null 表示跳过
     */
    public LlmStreamEvent parseLine(String dataLine, String provider) {
        if (dataLine == null || dataLine.isBlank()) {
            return null;
        }

        String trimmed = dataLine.trim();

        // [DONE] 终止标记（OpenAI 格式）
        if ("[DONE]".equalsIgnoreCase(trimmed)) {
            return LlmStreamEvent.done();
        }

        try {
            JsonNode root = MAPPER.readTree(trimmed);

            return switch (provider) {
                case "anthropic" -> parseAnthropicEvent(root);
                default -> parseOpenAiEvent(root);
            };
        } catch (Exception e) {
            LOG.debugf("跳过无法解析的 SSE 数据: %s", trimmed);
            return null;
        }
    }

    /**
     * 解析 OpenAI 格式 SSE 事件
     *
     * 格式：{"choices":[{"delta":{"content":"text"},"finish_reason":null}]}
     */
    private LlmStreamEvent parseOpenAiEvent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }

        JsonNode firstChoice = choices.get(0);
        String finishReason = firstChoice.path("finish_reason").asText(null);

        if ("stop".equals(finishReason)) {
            return LlmStreamEvent.done();
        }

        String content = firstChoice.path("delta").path("content").asText(null);
        if (content != null) {
            return LlmStreamEvent.delta(content);
        }

        return null;
    }

    /**
     * 解析 Anthropic 格式 SSE 事件
     *
     * 格式：
     * - content_block_delta: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
     * - message_stop: {"type":"message_stop"}
     */
    private LlmStreamEvent parseAnthropicEvent(JsonNode root) {
        String type = root.path("type").asText("");

        return switch (type) {
            case "content_block_delta" -> {
                String text = root.path("delta").path("text").asText(null);
                yield text != null ? LlmStreamEvent.delta(text) : null;
            }
            case "message_stop" -> LlmStreamEvent.done();
            case "error" -> {
                String message = root.path("error").path("message").asText("未知错误");
                yield LlmStreamEvent.error(message);
            }
            default -> null;
        };
    }
}
