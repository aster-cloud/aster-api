package io.aster.policy.audit;

import io.aster.policy.common.PIIRedactor;
import io.aster.policy.event.AuditEvent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志记录器
 *
 * 记录策略操作的审计日志，包括：
 * - 策略回滚操作
 * - 策略创建/更新操作
 * - 策略评估操作
 *
 * 日志写入流程：
 * 1. 应用日志（INFO 级别）- 实时监控，自动进行 PII 脱敏
 * 2. 发布 AuditEvent 异步事件 - 由 AuditEventListener 统一持久化
 */
@ApplicationScoped
public class AuditLogger {

    private static final Logger LOG = Logger.getLogger(AuditLogger.class);
    private final PIIRedactor piiRedactor = new PIIRedactor();
    @Inject
    Event<AuditEvent> auditEventPublisher;

    /**
     * 记录策略回滚操作
     *
     * @param policyId      策略ID
     * @param fromVersion   原版本号
     * @param toVersion     目标版本号
     * @param tenantId      租户ID
     * @param performedBy   执行者
     * @param reason        回滚原因
     */
    public void logRollback(
        String policyId,
        Long fromVersion,
        Long toVersion,
        String tenantId,
        String performedBy,
        String reason
    ) {
        Instant now = Instant.now();

        // 1. 写入应用日志
        Map<String, Object> auditLog = new HashMap<>();
        auditLog.put("eventType", "POLICY_ROLLBACK");
        auditLog.put("timestamp", now.toString());
        auditLog.put("policyId", policyId);
        auditLog.put("fromVersion", fromVersion);
        auditLog.put("toVersion", toVersion);
        auditLog.put("tenantId", tenantId);
        auditLog.put("performedBy", performedBy != null ? performedBy : "system");
        auditLog.put("reason", reason != null ? reason : "未提供原因");

        LOG.infof("AUDIT: %s", toJson(auditLog));

        // 2. 发布异步事件
        publishEvent(
            AuditEvent.rollback(
                tenantId,
                null,
                policyId,
                fromVersion,
                toVersion,
                performedBy,
                reason
            )
        );
    }

    /**
     * 记录策略创建操作
     *
     * @param policyId      策略ID
     * @param version       版本号
     * @param moduleName    模块名
     * @param functionName  函数名
     * @param tenantId      租户ID
     * @param createdBy     创建者
     */
    public void logPolicyCreation(
        String policyId,
        Long version,
        String moduleName,
        String functionName,
        String tenantId,
        String createdBy
    ) {
        Instant now = Instant.now();

        // 1. 写入应用日志
        Map<String, Object> auditLog = new HashMap<>();
        auditLog.put("eventType", "POLICY_CREATED");
        auditLog.put("timestamp", now.toString());
        auditLog.put("policyId", policyId);
        auditLog.put("version", version);
        auditLog.put("moduleName", moduleName);
        auditLog.put("functionName", functionName);
        auditLog.put("tenantId", tenantId);
        auditLog.put("createdBy", createdBy != null ? createdBy : "system");

        LOG.infof("AUDIT: %s", toJson(auditLog));

        // 2. 发布异步事件
        publishEvent(
            AuditEvent.deployment(
                tenantId,
                moduleName,
                policyId,
                functionName,
                createdBy,
                version
            )
        );
    }

    /**
     * 记录策略评估操作
     *
     * @param policyModule    策略模块
     * @param policyFunction  策略函数
     * @param tenantId        租户ID
     * @param executionTimeMs 执行时间（毫秒）
     * @param success         是否成功
     */
    public void logPolicyEvaluation(
        String policyModule,
        String policyFunction,
        String tenantId,
        long executionTimeMs,
        boolean success
    ) {
        Instant now = Instant.now();

        // 1. 写入应用日志
        Map<String, Object> auditLog = new HashMap<>();
        auditLog.put("eventType", "POLICY_EVALUATION");
        auditLog.put("timestamp", now.toString());
        auditLog.put("policyModule", policyModule);
        auditLog.put("policyFunction", policyFunction);
        auditLog.put("tenantId", tenantId);
        auditLog.put("executionTimeMs", executionTimeMs);
        auditLog.put("success", success);

        LOG.infof("AUDIT: %s", toJson(auditLog));

        // 2. 发布异步事件
        publishEvent(
            AuditEvent.policyEvaluation(
                tenantId,
                policyModule,
                policyFunction,
                "system",
                success,
                executionTimeMs,
                null,
                Collections.emptyMap()
            )
        );
    }

    /**
     * 将 Map 转换为简单的 JSON 字符串，并进行 PII 脱敏
     *
     * 注意：这是一个简化的实现，生产环境应使用 Jackson 等库。
     */
    private String toJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append("\"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                // 对字符串值进行 PII 脱敏
                String redactedValue = piiRedactor.redact((String) value);
                json.append("\"").append(redactedValue).append("\"");
            } else {
                json.append(value);
            }
        }
        json.append("}");
        return json.toString();
    }

    private void publishEvent(AuditEvent event) {
        try {
            auditEventPublisher.fireAsync(event)
                .exceptionally(throwable -> {
                    LOG.errorf(throwable, "Failed to publish audit event: %s", event.eventType());
                    return null;
                })
                .toCompletableFuture()
                .join();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to complete audit event publication: %s", event.eventType());
        }
    }
}
