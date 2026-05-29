package io.aster.policy.websocket;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.security.RateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点：策略预览
 *
 * 提供实时策略评估预览功能，支持：
 * - 接收策略代码和示例输入
 * - 实时编译和评估
 * - 返回评估结果或错误信息
 *
 * 防护措施：
 * - 连接级限流：限制每个 IP 的并发连接数
 * - 消息级限流：限制每个会话的消息频率
 * - 空闲超时：自动关闭长时间无消息的连接
 */
@ServerEndpoint("/ws/preview")
@ApplicationScoped
public class PreviewWebSocket {

    private static final Logger LOG = Logger.getLogger(PreviewWebSocket.class);
    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    @Inject
    PolicyEvaluationService evaluationService;

    @Inject
    RateLimiter rateLimiter;

    @ConfigProperty(name = "aster.ratelimit.enabled", defaultValue = "true")
    boolean rateLimitEnabled;

    @ConfigProperty(name = "aster.ratelimit.ws.max-connections", defaultValue = "5")
    int maxConnections;

    @ConfigProperty(name = "aster.ratelimit.ws.max-messages-per-second", defaultValue = "10")
    int maxMessagesPerSecond;

    @ConfigProperty(name = "aster.ratelimit.ws.idle-timeout-ms", defaultValue = "300000")
    long idleTimeoutMs;

    // 存储会话，用于管理
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // 会话 → 连接标识映射（用于连接释放时减计数）
    private final ConcurrentHashMap<String, String> sessionIdentifiers = new ConcurrentHashMap<>();

    /**
     * 连接建立
     */
    @OnOpen
    public void onOpen(Session session) {
        // 设置空闲超时
        session.setMaxIdleTimeout(idleTimeoutMs);

        // 连接级限流
        String identifier = extractIdentifier(session);

        if (rateLimitEnabled && !rateLimiter.tryAcquireConnection(identifier, maxConnections)) {
            LOG.warnf("WebSocket 连接限流拒绝: identifier=%s, session=%s", identifier, session.getId());
            closeSession(session, new CloseReason(
                CloseReason.CloseCodes.VIOLATED_POLICY,
                "Connection limit exceeded"
            ));
            return;
        }

        sessions.put(session.getId(), session);
        sessionIdentifiers.put(session.getId(), identifier);

        LOG.infof("WebSocket connected: %s (identifier=%s)", session.getId(), identifier);
        sendMessage(session, createResponse("connected", "WebSocket 连接成功", null));
    }

    /**
     * 连接关闭
     */
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());

        // 释放连接计数
        String identifier = sessionIdentifiers.remove(session.getId());
        if (identifier != null) {
            rateLimiter.releaseConnection(identifier);
        }

        LOG.infof("WebSocket closed: %s", session.getId());
    }

    /**
     * 错误处理
     */
    @OnError
    public void onError(Session session, Throwable throwable) {
        LOG.errorf(throwable, "WebSocket error for session %s", session.getId());
        sendMessage(session, createResponse("error", throwable.getMessage(), null));
    }

    /**
     * 接收消息
     *
     * 消息格式：
     * {
     *   "policyModule": "aster.finance.loan",
     *   "policyFunction": "evaluateLoanEligibility",
     *   "context": [{"key": "value"}]
     * }
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        LOG.debugf("Received message from %s: %s", session.getId(), message);

        // 消息级限流
        if (rateLimitEnabled) {
            String msgRateLimitKey = "ws:msg:" + session.getId();
            if (!rateLimiter.tryAcquire(msgRateLimitKey, maxMessagesPerSecond, Duration.ofSeconds(1))) {
                LOG.warnf("WebSocket 消息限流拒绝: session=%s", session.getId());
                sendMessage(session, createResponse("error", "Rate limit exceeded", null));
                return;
            }
        }

        try {
            // 解析请求
            JsonNode request = MAPPER.readTree(message);

            String policyModule = request.path("policyModule").asText();
            String policyFunction = request.path("policyFunction").asText();

            // 解析 context（JSON 数组转换为 Object[]）
            JsonNode contextNode = request.path("context");
            Object[] context;

            if (contextNode.isArray()) {
                List<Object> contextList = MAPPER.convertValue(contextNode,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Object.class));
                context = contextList.toArray(new Object[0]);
            } else {
                // 如果不是数组，包装成单元素数组
                Object singleContext = MAPPER.convertValue(contextNode, Object.class);
                context = new Object[]{singleContext};
            }

            // 验证必需字段
            if (policyModule.isEmpty() || policyFunction.isEmpty()) {
                sendMessage(session, createResponse("error", "缺少必需字段: policyModule 或 policyFunction", null));
                return;
            }

            // 异步评估策略
            long startTime = System.currentTimeMillis();

            evaluationService.evaluatePolicy(
                    "preview", // 使用专用租户ID
                    policyModule,
                    policyFunction,
                    context
            )
            .subscribe().with(
                result -> {
                    long executionTime = System.currentTimeMillis() - startTime;

                    // 构建成功响应
                    PreviewResponse response = new PreviewResponse(
                        "success",
                        "评估成功",
                        result.getResult(),
                        executionTime
                    );

                    sendMessage(session, response);

                    LOG.infof("Preview evaluation completed in %dms: %s.%s",
                        executionTime, policyModule, policyFunction);
                },
                throwable -> {
                    long executionTime = System.currentTimeMillis() - startTime;

                    // 构建错误响应
                    sendMessage(session, createResponse("error", throwable.getMessage(), executionTime));

                    LOG.errorf(throwable, "Preview evaluation failed after %dms: %s.%s",
                        executionTime, policyModule, policyFunction);
                }
            );

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process preview request from %s", session.getId());
            sendMessage(session, createResponse("error", "请求格式错误: " + e.getMessage(), null));
        }
    }

    /**
     * 从 WebSocket 会话中提取连接标识（IP 地址）
     */
    private String extractIdentifier(Session session) {
        // 优先从 query parameter 中获取标识
        var params = session.getRequestParameterMap();
        if (params != null && params.containsKey("clientId")) {
            List<String> values = params.get("clientId");
            if (values != null && !values.isEmpty()) {
                return "ws:" + values.get(0);
            }
        }

        // 回退到会话用户属性或远程地址
        var userProperties = session.getUserProperties();
        if (userProperties != null) {
            Object remoteAddr = userProperties.get("jakarta.websocket.endpoint.remoteAddress");
            if (remoteAddr != null) {
                return "ws:" + remoteAddr;
            }
        }

        // 最终回退：使用固定前缀（所有匿名连接共享限额）
        return "ws:anonymous";
    }

    /**
     * 安全关闭 WebSocket 会话
     */
    private void closeSession(Session session, CloseReason reason) {
        try {
            session.close(reason);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to close session %s", session.getId());
        }
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(Session session, Object response) {
        try {
            String json = MAPPER.writeValueAsString(response);
            session.getAsyncRemote().sendText(json);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send message to session %s", session.getId());
        }
    }

    /**
     * 创建响应对象
     */
    private PreviewResponse createResponse(String status, String message, Long executionTime) {
        return new PreviewResponse(status, message, null, executionTime);
    }

    /**
     * 预览响应
     */
    public record PreviewResponse(
        String status,      // "connected", "success", "error"
        String message,     // 消息描述
        Object result,      // 评估结果（仅 success 时有值）
        Long executionTime  // 执行时间（毫秒）
    ) {}
}
