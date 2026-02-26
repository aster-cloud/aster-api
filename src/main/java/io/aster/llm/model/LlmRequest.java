package io.aster.llm.model;

import java.util.List;

/**
 * LLM API 调用请求
 *
 * 统一抽象 OpenAI / Anthropic / 自定义 API 的请求格式。
 * 实际 HTTP 请求体由 LlmClient 实现根据 provider 转换。
 *
 * @param model       模型名
 * @param messages    消息列表（System / Developer / User）
 * @param temperature 温度参数
 * @param maxTokens   最大输出 token 数
 * @param stream      是否流式
 */
public record LlmRequest(
    String model,
    List<Message> messages,
    double temperature,
    int maxTokens,
    boolean stream
) {
    /**
     * 消息
     *
     * @param role    角色：system / developer / user / assistant
     * @param content 消息内容
     */
    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message developer(String content) {
            return new Message("developer", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
