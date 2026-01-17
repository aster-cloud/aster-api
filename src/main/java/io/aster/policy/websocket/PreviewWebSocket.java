package io.aster.policy.websocket;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.rest.model.EvaluationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点：策略预览
 *
 * 提供实时策略评估预览功能，支持：
 * - 接收策略代码和示例输入
 * - 实时编译和评估
 * - 返回评估结果或错误信息
 */
@ServerEndpoint("/ws/preview")
@ApplicationScoped
public class PreviewWebSocket {

    private static final Logger LOG = Logger.getLogger(PreviewWebSocket.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PolicyEvaluationService evaluationService;

    // 存储会话，用于广播
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 连接建立
     */
    @OnOpen
    public void onOpen(Session session) {
        sessions.put(session.getId(), session);
        LOG.infof("WebSocket connected: %s", session.getId());
        sendMessage(session, createResponse("connected", "WebSocket 连接成功", null));
    }

    /**
     * 连接关闭
     */
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session.getId());
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
