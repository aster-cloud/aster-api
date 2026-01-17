package io.aster.policy.event;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计事件 - 不可变事件对象
 *
 * 用于在业务操作完成后发布，由 AuditEventListener 异步处理并持久化。
 * Phase 3.7 扩展：添加异常响应相关字段（anomalyId, actionType, oldStatus, newStatus）
 */
public record AuditEvent(
    EventType eventType,
    Instant timestamp,
    String tenantId,
    String policyModule,
    String policyFunction,
    String policyId,
    Long fromVersion,
    Long toVersion,
    String performedBy,
    boolean success,
    Long executionTimeMs,
    String errorMessage,
    Map<String, Object> metadata,

    // Phase 3.7: 异常响应自动化字段
    Long anomalyId,      // 异常报告 ID
    String actionType,   // 动作类型（VERIFY_REPLAY, AUTO_ROLLBACK）
    String oldStatus,    // 变更前状态
    String newStatus     // 变更后状态
) {
    /**
     * 创建策略评估事件。
     */
    public static AuditEvent policyEvaluation(
        String tenantId,
        String policyModule,
        String policyFunction,
        String performedBy,
        boolean success,
        Long executionTimeMs,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        return new AuditEvent(
            EventType.POLICY_EVALUATION,
            Instant.now(),
            tenantId,
            policyModule,
            policyFunction,
            null,
            null,
            null,
            performedBy,
            success,
            executionTimeMs,
            errorMessage,
            sanitizeMetadata(metadata),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    /**
     * 创建回滚事件。
     */
    public static AuditEvent rollback(
        String tenantId,
        String policyModule,
        String policyId,
        Long fromVersion,
        Long targetVersion,
        String performedBy,
        String reason
    ) {
        return new AuditEvent(
            EventType.POLICY_ROLLBACK,
            Instant.now(),
            tenantId,
            policyModule,
            null,
            policyId,
            fromVersion,
            targetVersion,
            performedBy,
            true,
            null,
            null,
            buildRollbackMetadata(policyId, fromVersion, targetVersion, reason),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    /**
     * 创建部署事件。
     */
    public static AuditEvent deployment(
        String tenantId,
        String policyModule,
        String policyId,
        String policyFunction,
        String performedBy,
        Long version
    ) {
        Map<String, Object> metadata = version == null
            ? Collections.emptyMap()
            : Map.of("version", version);
        return new AuditEvent(
            EventType.POLICY_CREATED,
            Instant.now(),
            tenantId,
            policyModule,
            policyFunction,
            policyId,
            null,
            version,
            performedBy,
            true,
            null,
            null,
            metadata,
            null, null, null, null  // Phase 3.7 fields
        );
    }

    /**
     * 创建订单提交事件。
     */
    public static AuditEvent orderSubmission(
        String tenantId,
        String workflowModule,
        String workflowFunction,
        String orderId,
        String workflowId,
        String performedBy,
        boolean success,
        Long executionTimeMs,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        Map<String, Object> enriched = new HashMap<>();
        if (metadata != null) {
            enriched.putAll(metadata);
        }
        enriched.put("workflowId", workflowId);
        enriched.put("orderId", orderId);

        return new AuditEvent(
            EventType.ORDER_SUBMITTED,
            Instant.now(),
            tenantId,
            workflowModule,
            workflowFunction,
            orderId,
            null,
            null,
            performedBy,
            success,
            executionTimeMs,
            errorMessage,
            sanitizeMetadata(enriched),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    /**
     * 创建订单状态查询事件。
     */
    public static AuditEvent orderStatusQuery(
        String tenantId,
        String workflowModule,
        String workflowFunction,
        String orderId,
        String workflowId,
        String performedBy,
        String status,
        boolean success,
        Long executionTimeMs,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        Map<String, Object> enriched = new HashMap<>();
        if (metadata != null) {
            enriched.putAll(metadata);
        }
        enriched.put("workflowId", workflowId);
        enriched.put("orderId", orderId);
        enriched.put("status", status);

        return new AuditEvent(
            EventType.ORDER_STATUS_QUERIED,
            Instant.now(),
            tenantId,
            workflowModule,
            workflowFunction,
            orderId,
            null,
            null,
            performedBy,
            success,
            executionTimeMs,
            errorMessage,
            sanitizeMetadata(enriched),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    // ==================== Phase 3.7: 异常响应自动化事件 ====================

    /**
     * 创建异常验证事件（Phase 3.7）
     *
     * @param policyId   策略 ID
     * @param anomalyId  异常报告 ID
     * @param actionType 动作类型（VERIFY_REPLAY, AUTO_ROLLBACK）
     * @return 异常验证事件
     */
    public static AuditEvent anomalyVerification(
        String policyId,
        Long anomalyId,
        String actionType
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("anomalyId", anomalyId);
        metadata.put("actionType", actionType);

        return new AuditEvent(
            EventType.ANOMALY_VERIFICATION,
            Instant.now(),
            null,  // tenantId
            null,  // policyModule
            null,  // policyFunction
            policyId,
            null,  // fromVersion
            null,  // toVersion
            "SYSTEM",  // performedBy (自动化系统)
            true,
            null,  // executionTimeMs
            null,  // errorMessage
            Collections.unmodifiableMap(metadata),
            anomalyId,
            actionType,
            null,  // oldStatus
            null   // newStatus
        );
    }

    /**
     * 创建异常状态变更事件（Phase 3.7）
     *
     * @param policyId  策略 ID
     * @param anomalyId 异常报告 ID
     * @param oldStatus 变更前状态
     * @param newStatus 变更后状态
     * @param notes     变更备注
     * @return 异常状态变更事件
     */
    public static AuditEvent anomalyStatusChange(
        String policyId,
        Long anomalyId,
        String oldStatus,
        String newStatus,
        String notes
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("anomalyId", anomalyId);
        metadata.put("oldStatus", oldStatus);
        metadata.put("newStatus", newStatus);
        if (notes != null && !notes.isBlank()) {
            metadata.put("notes", notes);
        }

        return new AuditEvent(
            EventType.ANOMALY_STATUS_CHANGE,
            Instant.now(),
            null,  // tenantId
            null,  // policyModule
            null,  // policyFunction
            policyId,
            null,  // fromVersion
            null,  // toVersion
            "SYSTEM",  // performedBy
            true,
            null,  // executionTimeMs
            null,  // errorMessage
            Collections.unmodifiableMap(metadata),
            anomalyId,
            null,  // actionType
            oldStatus,
            newStatus
        );
    }

    /**
     * 创建异常自动回滚事件（Phase 3.7）
     *
     * @param policyId    策略 ID
     * @param anomalyId   异常报告 ID
     * @param fromVersion 回滚前版本
     * @param toVersion   回滚目标版本
     * @return 异常自动回滚事件
     */
    public static AuditEvent anomalyAutoRollback(
        String policyId,
        Long anomalyId,
        Long fromVersion,
        Long toVersion
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("anomalyId", anomalyId);
        metadata.put("actionType", "AUTO_ROLLBACK");
        metadata.put("fromVersion", fromVersion);
        metadata.put("toVersion", toVersion);

        return new AuditEvent(
            EventType.ANOMALY_AUTO_ROLLBACK,
            Instant.now(),
            null,  // tenantId
            null,  // policyModule
            null,  // policyFunction
            policyId,
            fromVersion,
            toVersion,
            "SYSTEM",  // performedBy (自动化系统)
            true,
            null,  // executionTimeMs
            null,  // errorMessage
            Collections.unmodifiableMap(metadata),
            anomalyId,
            "AUTO_ROLLBACK",
            null,  // oldStatus
            null   // newStatus
        );
    }

    private static Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> sanitized = new HashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(sanitized);
    }

    private static Map<String, Object> buildRollbackMetadata(
        String policyId,
        Long fromVersion,
        Long targetVersion,
        String reason
    ) {
        Map<String, Object> metadata = new HashMap<>();
        if (policyId != null && !policyId.isBlank()) {
            metadata.put("policyId", policyId);
        }
        if (fromVersion != null) {
            metadata.put("fromVersion", fromVersion);
        }
        if (targetVersion != null) {
            metadata.put("targetVersion", targetVersion);
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        return metadata.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }
}
