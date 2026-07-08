package io.aster.llm.client;

import io.aster.llm.model.LlmStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
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
    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

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
        // issue #185：开启 stream_options.include_usage 后，最后一帧 choices 为空、根部带 usage。
        // 该帧要产出 USAGE 事件（真实 token）。注意：不能在 finish_reason=stop 时就 DONE，否则会在
        // usage 帧到达前提前结束流 → 拿不到 token（Codex 审查）。真正的结束由 [DONE] 标记触发。
        if (!choices.isArray() || choices.isEmpty()) {
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                int prompt = usage.path("prompt_tokens").asInt(0);
                int completion = usage.path("completion_tokens").asInt(0);
                if (prompt > 0 || completion > 0) {
                    return LlmStreamEvent.usage(new io.aster.llm.model.LlmUsage(prompt, completion));
                }
            }
            return null;
        }

        JsonNode firstChoice = choices.get(0);
        String content = firstChoice.path("delta").path("content").asText(null);
        if (content != null) {
            return LlmStreamEvent.delta(content);
        }

        // finish_reason=stop 不再直接 DONE：继续等 usage 帧 + [DONE] 标记（见上）。
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
            // issue #185：Anthropic 流式 usage 分散在 message_start(input_tokens) 与
            // message_delta(output_tokens 累计绝对值)。各产出一个 USAGE 事件，业务层 attempt 内取
            // max（累计 output 取 latest，避免 overcount）、attempt 间 plus（LlmProxyService）。
            case "message_start" -> {
                int input = root.path("message").path("usage").path("input_tokens").asInt(0);
                yield input > 0 ? LlmStreamEvent.usage(new io.aster.llm.model.LlmUsage(input, 0)) : null;
            }
            case "message_delta" -> {
                int output = root.path("usage").path("output_tokens").asInt(0);
                yield output > 0 ? LlmStreamEvent.usage(new io.aster.llm.model.LlmUsage(0, output)) : null;
            }
            case "message_stop" -> LlmStreamEvent.done();
            case "error" -> {
                // 红队硬化：provider（Anthropic）的 error.message 可能含 key 前缀/鉴权诊断/请求
                // 片段，记日志但不回显前端，前端只给泛化文案（与 VertxLlmClient 非 200 脱敏一致）。
                String errType = root.path("error").path("type").asText("");
                LOG.errorf("Anthropic SSE error 帧: type=%s (message 已脱敏不回显)", errType);
                yield LlmStreamEvent.error("LLM 流式响应错误");
            }
            default -> null;
        };
    }
}
