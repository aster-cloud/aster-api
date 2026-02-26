package io.aster.llm.client;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmRequest;
import io.aster.llm.model.LlmStreamEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Vert.x WebClient 实现的 LLM 客户端
 *
 * 使用 Vert.x WebClient 而非 LangChain4j，确保 GraalVM Native 兼容。
 * 流式模式下手动解析 SSE text/event-stream 响应体。
 */
@ApplicationScoped
public class VertxLlmClient implements LlmClient {

    private static final Logger LOG = Logger.getLogger(VertxLlmClient.class);

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    LlmConfig config;

    @Inject
    SseEventParser sseParser;

    private volatile WebClient webClient;

    private WebClient getClient() {
        if (webClient == null) {
            synchronized (this) {
                if (webClient == null) {
                    webClient = WebClient.create(mutinyVertx.getDelegate());
                }
            }
        }
        return webClient;
    }

    @Override
    public Multi<LlmStreamEvent> streamChat(LlmRequest request, String apiKey) {
        return Multi.createFrom().emitter(emitter -> {
            try {
                URI baseUri = URI.create(config.baseUrl());
                String chatPath = resolveChatPath(baseUri, config.provider());
                JsonObject body = buildRequestBody(request);
                int port = resolvePort(baseUri);

                LOG.infof("LLM 流式请求: provider=%s, model=%s, url=%s:%d%s",
                    config.provider(), request.model(), baseUri.getHost(), port, chatPath);

                HttpRequest<Buffer> httpRequest = getClient()
                    .request(HttpMethod.POST, port, baseUri.getHost(), chatPath)
                    .ssl("https".equals(baseUri.getScheme()))
                    .timeout(config.readTimeout().toMillis())
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Accept", "text/event-stream");

                addAuthHeaders(httpRequest, apiKey, config.provider());

                httpRequest.sendJsonObject(body, ar -> {
                    if (ar.failed()) {
                        LOG.errorf(ar.cause(), "LLM 请求失败");
                        emitter.emit(LlmStreamEvent.error("LLM 请求失败: " + ar.cause().getMessage()));
                        emitter.complete();
                        return;
                    }

                    HttpResponse<Buffer> response = ar.result();
                    int statusCode = response.statusCode();

                    if (statusCode != 200) {
                        String errorBody = response.body() != null ? response.bodyAsString() : "(empty)";
                        LOG.errorf("LLM API 错误: status=%d, body=%s", statusCode, errorBody);
                        emitter.emit(LlmStreamEvent.error("LLM API 错误 (" + statusCode + "): " + errorBody));
                        emitter.complete();
                        return;
                    }

                    if (response.body() == null) {
                        emitter.emit(LlmStreamEvent.error("LLM API 返回空响应"));
                        emitter.complete();
                        return;
                    }

                    parseSseResponse(response.bodyAsString(), emitter);
                });

            } catch (Exception e) {
                LOG.errorf(e, "LLM 流式请求异常");
                emitter.emit(LlmStreamEvent.error("请求异常: " + e.getMessage()));
                emitter.complete();
            }
        });
    }

    @Override
    public Uni<String> chat(LlmRequest request, String apiKey) {
        URI baseUri = URI.create(config.baseUrl());
        String chatPath = resolveChatPath(baseUri, config.provider());
        int port = resolvePort(baseUri);

        LlmRequest nonStreamRequest = new LlmRequest(
            request.model(), request.messages(),
            request.temperature(), request.maxTokens(), false
        );
        JsonObject body = buildRequestBody(nonStreamRequest);

        return Uni.createFrom().emitter(emitter -> {
            HttpRequest<Buffer> httpRequest = getClient()
                .request(HttpMethod.POST, port, baseUri.getHost(), chatPath)
                .ssl("https".equals(baseUri.getScheme()))
                .timeout(config.timeout().toMillis())
                .putHeader("Content-Type", "application/json");

            addAuthHeaders(httpRequest, apiKey, config.provider());

            httpRequest.sendJsonObject(body, ar -> {
                if (ar.failed()) {
                    emitter.fail(ar.cause());
                    return;
                }

                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    String errorBody = response.body() != null ? response.bodyAsString() : "(empty)";
                    emitter.fail(new RuntimeException(
                        "LLM API 错误 (" + response.statusCode() + "): " + errorBody));
                    return;
                }

                try {
                    JsonObject responseJson = response.bodyAsJsonObject();
                    String content = extractContent(responseJson, config.provider());
                    emitter.complete(content);
                } catch (Exception e) {
                    emitter.fail(new RuntimeException("LLM 响应解析失败", e));
                }
            });
        });
    }

    private void parseSseResponse(String responseBody, io.smallrye.mutiny.subscription.MultiEmitter<? super LlmStreamEvent> emitter) {
        // 使用 \\r?\\n 处理 CRLF 和 LF 两种行尾
        for (String line : responseBody.split("\\r?\\n")) {
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                continue;
            }

            if (trimmedLine.startsWith("data: ") || trimmedLine.startsWith("data:")) {
                String data = trimmedLine.startsWith("data: ")
                    ? trimmedLine.substring(6)
                    : trimmedLine.substring(5);

                LlmStreamEvent event = sseParser.parseLine(data, config.provider());
                if (event != null) {
                    if (event.type() == LlmStreamEvent.Type.DONE) {
                        emitter.complete();
                        return;
                    }
                    emitter.emit(event);
                }
            }
        }

        emitter.complete();
    }

    private JsonObject buildRequestBody(LlmRequest request) {
        JsonArray messages = new JsonArray();
        boolean isNativeOpenAi = "openai".equals(config.provider())
            && config.baseUrl().contains("api.openai.com");
        for (var msg : request.messages()) {
            String role = msg.role();
            if ("developer".equals(role)) {
                // "developer" 角色仅 OpenAI 原生 API 支持，
                // 其他提供商和 OpenAI 兼容代理需映射为 system
                if ("anthropic".equals(config.provider())) {
                    role = "user";
                } else if (!isNativeOpenAi) {
                    role = "system";
                }
            }
            messages.add(new JsonObject()
                .put("role", role)
                .put("content", msg.content()));
        }

        return new JsonObject()
            .put("model", request.model())
            .put("messages", messages)
            .put("temperature", request.temperature())
            .put("max_tokens", request.maxTokens())
            .put("stream", request.stream());
    }

    private void addAuthHeaders(HttpRequest<Buffer> request, String apiKey, String provider) {
        switch (provider) {
            case "anthropic" -> {
                request.putHeader("x-api-key", apiKey);
                request.putHeader("anthropic-version", "2023-06-01");
            }
            default -> request.putHeader("Authorization", "Bearer " + apiKey);
        }
    }

    /**
     * 解析完整的 API 路径
     *
     * 如果 base-url 包含路径前缀（如 https://right.codes/codex/v1），
     * 则只追加端点后缀（/chat/completions），避免重复 /v1。
     */
    private String resolveChatPath(URI baseUri, String provider) {
        String basePath = baseUri.getPath();
        if (basePath == null) basePath = "";
        // 去掉尾部斜杠
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);

        // 如果 base URL 已包含路径（如 /codex/v1），则只追加端点后缀
        if (!basePath.isEmpty()) {
            return switch (provider) {
                case "anthropic" -> basePath + "/messages";
                default -> basePath + "/chat/completions";
            };
        }

        // 标准 base URL（如 https://api.openai.com），追加完整路径
        return switch (provider) {
            case "anthropic" -> "/v1/messages";
            default -> "/v1/chat/completions";
        };
    }

    private int resolvePort(URI uri) {
        int port = uri.getPort();
        if (port > 0) return port;
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private String extractContent(JsonObject body, String provider) {
        return switch (provider) {
            case "anthropic" -> {
                JsonArray content = body.getJsonArray("content");
                if (content == null || content.isEmpty()) {
                    yield "";
                }
                yield content.getJsonObject(0).getString("text", "");
            }
            default -> {
                JsonArray choices = body.getJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    yield "";
                }
                JsonObject message = choices.getJsonObject(0).getJsonObject("message");
                yield message != null ? message.getString("content", "") : "";
            }
        };
    }
}
