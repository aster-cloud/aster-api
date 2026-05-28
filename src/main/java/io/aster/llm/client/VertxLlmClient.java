package io.aster.llm.client;

import io.aster.llm.config.LlmConfig;
import io.aster.llm.metrics.LlmMetrics;
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
    io.aster.common.http.SharedWebClient sharedWebClient;

    @Inject
    LlmConfig config;

    @Inject
    SseEventParser sseParser;

    @Inject
    LlmMetrics llmMetrics;

    // P0-R19: WebClient DCL consolidated into SharedWebClient.
    // mutinyVertx still injected for createHttpClient (non-WebClient path).
    private WebClient getClient() {
        return sharedWebClient.client();
    }

    @Override
    public Multi<LlmStreamEvent> streamChat(LlmRequest request, String apiKey) {
        return Multi.createFrom().emitter(emitter -> {
            try {
                URI baseUri = URI.create(config.baseUrl());
                String chatPath = resolveChatPath(baseUri, config.provider());
                JsonObject body = buildRequestBody(request);
                int port = resolvePort(baseUri);
                boolean ssl = "https".equals(baseUri.getScheme());

                LOG.infof("LLM 流式请求: provider=%s, model=%s, url=%s:%d%s",
                    config.provider(), request.model(), baseUri.getHost(), port, chatPath);

                // 使用底层 HttpClient 获取 response stream，避免 WebClient.sendJsonObject 缓冲整个响应体
                io.vertx.core.http.HttpClientOptions clientOptions = new io.vertx.core.http.HttpClientOptions()
                    .setSsl(ssl)
                    .setDefaultHost(baseUri.getHost())
                    .setDefaultPort(port)
                    .setConnectTimeout((int) config.readTimeout().toMillis());

                io.vertx.core.http.HttpClient httpClient = mutinyVertx.getDelegate().createHttpClient(clientOptions);
                io.vertx.core.http.RequestOptions reqOptions = new io.vertx.core.http.RequestOptions()
                    .setMethod(HttpMethod.POST)
                    .setURI(chatPath)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Accept", "text/event-stream");

                // 注入认证头
                switch (config.provider()) {
                    case "anthropic" -> {
                        reqOptions.putHeader("x-api-key", apiKey);
                        reqOptions.putHeader("anthropic-version", "2023-06-01");
                    }
                    default -> reqOptions.putHeader("Authorization", "Bearer " + apiKey);
                }

                // 用于跨 chunk 拼接不完整的字节（防止多字节 UTF-8 字符被截断）
                // 使用数组包装以支持 lambda 内重新赋值
                io.vertx.core.buffer.Buffer[] byteBufferHolder = { io.vertx.core.buffer.Buffer.buffer() };

                // 下游取消时关闭 HTTP 连接，释放资源并停止 token 消耗
                emitter.onTermination(() -> {
                    httpClient.close();
                });

                httpClient.request(reqOptions, reqAr -> {
                    if (reqAr.failed()) {
                        LOG.errorf(reqAr.cause(), "LLM 连接失败");
                        emitter.emit(LlmStreamEvent.error("LLM 连接失败: " + reqAr.cause().getMessage()));
                        emitter.complete();
                        httpClient.close();
                        return;
                    }

                    io.vertx.core.http.HttpClientRequest clientRequest = reqAr.result();
                    clientRequest.response(respAr -> {
                        if (respAr.failed()) {
                            LOG.errorf(respAr.cause(), "LLM 响应读取失败");
                            emitter.emit(LlmStreamEvent.error("LLM 响应读取失败: " + respAr.cause().getMessage()));
                            emitter.complete();
                            httpClient.close();
                            return;
                        }

                        io.vertx.core.http.HttpClientResponse clientResponse = respAr.result();
                        int statusCode = clientResponse.statusCode();

                        if (statusCode != 200) {
                            // 非 200：缓冲错误体后关闭
                            clientResponse.bodyHandler(errBuf -> {
                                String errBody = errBuf != null ? errBuf.toString() : "(empty)";
                                LOG.errorf("LLM API 错误: status=%d, body=%s", statusCode, errBody);
                                emitter.emit(LlmStreamEvent.error("LLM API 错误 (" + statusCode + "): " + errBody));
                                emitter.complete();
                                httpClient.close();
                            });
                            return;
                        }

                        // 逐 chunk 解析 SSE，使用 Buffer 拼接避免多字节 UTF-8 字符被截断
                        clientResponse.handler(chunk -> {
                            byteBufferHolder[0].appendBuffer(chunk);
                            // 安全地转换为字符串并逐行解析
                            String text = byteBufferHolder[0].toString();
                            int lastNewline = text.lastIndexOf('\n');
                            if (lastNewline < 0) return; // 没有完整行，等待下一个 chunk

                            // 处理已完成的行，保留不完整的尾部
                            String completePart = text.substring(0, lastNewline + 1);
                            String remainder = text.substring(lastNewline + 1);
                            byteBufferHolder[0] = io.vertx.core.buffer.Buffer.buffer(remainder);

                            for (String line : completePart.split("\n", -1)) {
                                line = line.stripTrailing();
                                if (line.startsWith("data: ") || line.startsWith("data:")) {
                                    String data = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                                    LlmStreamEvent event = sseParser.parseLine(data, config.provider());
                                    if (event != null) {
                                        if (event.type() == LlmStreamEvent.Type.DONE) {
                                            emitter.complete();
                                            httpClient.close();
                                            return;
                                        }
                                        emitter.emit(event);
                                    }
                                }
                            }
                        });

                        clientResponse.endHandler(v -> {
                            // 处理尾部缓冲区中的最后一行（没有换行符结尾的情况）
                            if (byteBufferHolder[0].length() > 0) {
                                String tail = byteBufferHolder[0].toString().stripTrailing();
                                if (tail.startsWith("data: ") || tail.startsWith("data:")) {
                                    String data = tail.startsWith("data: ") ? tail.substring(6) : tail.substring(5);
                                    LlmStreamEvent event = sseParser.parseLine(data, config.provider());
                                    if (event != null && event.type() != LlmStreamEvent.Type.DONE) {
                                        emitter.emit(event);
                                    }
                                }
                            }
                            emitter.complete();
                            httpClient.close();
                        });

                        clientResponse.exceptionHandler(ex -> {
                            LOG.errorf(ex, "LLM 流式响应异常");
                            emitter.emit(LlmStreamEvent.error("流式响应异常: " + ex.getMessage()));
                            emitter.complete();
                            httpClient.close();
                        });
                    });

                    clientRequest.end(io.vertx.core.buffer.Buffer.buffer(body.encode()));
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
                    recordTokenUsage(responseJson, request.model());
                    emitter.complete(content);
                } catch (Exception e) {
                    emitter.fail(new RuntimeException("LLM 响应解析失败", e));
                }
            });
        });
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

    /**
     * 从非流式响应中解析 token 用量并写入 Counter
     *
     * 与 OpenAI 兼容的 provider 在响应根部返回 usage 字段；流式 SSE 通常不返回，
     * 因此此方法仅在非流式分支调用。
     */
    private void recordTokenUsage(JsonObject body, String model) {
        if (body == null) return;
        JsonObject usage = body.getJsonObject("usage");
        if (usage == null) return;
        int prompt = usage.getInteger("prompt_tokens", 0);
        int completion = usage.getInteger("completion_tokens", 0);
        if (prompt > 0 || completion > 0) {
            llmMetrics.recordTokens(model != null ? model : "unknown", prompt, completion);
        }
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
