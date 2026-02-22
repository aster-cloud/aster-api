package io.aster.policy.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.common.PIIRedactor;
import io.aster.monitoring.BusinessMetrics;
import io.aster.policy.entity.AuditLog;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计事件监听器 - 异步处理审计事件并持久化。
 */
@ApplicationScoped
public class AuditEventListener {

    private static final int MAX_METADATA_SIZE = 4096;
    private final PIIRedactor piiRedactor = new PIIRedactor();
    @Inject
    ObjectMapper objectMapper;

    @Inject
    BusinessMetrics businessMetrics;

    /**
     * 监听审计事件并持久化。
     *
     * 使用 @ObservesAsync 实现异步处理，避免阻塞主业务流程。
     * 注意：异步事件不支持 TransactionPhase，需要在新事务中处理。
     */
    @Transactional
    public void onAuditEvent(@ObservesAsync AuditEvent event) {
        try {
            AuditLog log = new AuditLog();
            log.eventType = event.eventType().name();
            log.timestamp = event.timestamp().truncatedTo(ChronoUnit.MICROS);
            String tenant = event.tenantId();
            if (tenant == null || tenant.isBlank()) {
                tenant = "system";
            }
            log.tenantId = redact(tenant);
            log.policyModule = redact(event.policyModule());
            log.policyFunction = redact(event.policyFunction());
            log.policyId = redact(event.policyId());
            log.fromVersion = event.fromVersion();
            log.toVersion = event.toVersion();
            log.performedBy = redact(event.performedBy());
            log.success = event.success();
            log.executionTimeMs = event.executionTimeMs();
            log.errorMessage = event.errorMessage();
            log.reason = extractReason(event.metadata());
            log.metadata = serializeMetadata(event.metadata());

            // 计算哈希链（Phase 0 Task 3.2）
            computeHashChain(log);

            log.persist();
            businessMetrics.recordAuditLogWrite();

            Log.debugf(
                "Audit event persisted: type=%s, tenant=%s, module=%s, hash=%s",
                event.eventType(),
                event.tenantId(),
                event.policyModule(),
                log.currentHash
            );
        } catch (Exception e) {
            Log.errorf(
                e,
                "Failed to persist audit event: type=%s, tenant=%s",
                event.eventType(),
                event.tenantId()
            );
        }
    }

    private String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return piiRedactor.redact(value);
    }

    private String extractReason(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object reason = metadata.get("reason");
        return reason != null ? redact(reason.toString()) : null;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> redactedMetadata = new HashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (value instanceof String stringValue) {
                redactedMetadata.put(key, redact(stringValue));
            } else {
                redactedMetadata.put(key, value);
            }
        });
        if (redactedMetadata.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(redactedMetadata);
            if (json.length() > MAX_METADATA_SIZE) {
                Log.warnf(
                    "Metadata too large (%d bytes), truncating to %d bytes",
                    json.length(),
                    MAX_METADATA_SIZE
                );
                int safeLength = Math.max(0, MAX_METADATA_SIZE - 13);
                json = json.substring(0, safeLength) + "...truncated";
            }
            return json;
        } catch (Exception e) {
            Log.errorf(e, "Failed to serialize metadata");
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    /**
     * 计算审计记录的哈希链（Phase 0 Task 3.2）
     *
     * 实现 per-tenant 哈希链，避免全局竞争。
     * 每条记录包含：
     * - prevHash: 前一条记录的哈希值（genesis block 为 null）
     * - currentHash: 当前记录的哈希值
     *
     * 哈希计算规则：SHA256(prev_hash + event_type + timestamp + tenant_id + policy_module + policy_function + success)
     */
    private void computeHashChain(AuditLog log) {
        try {
            // 查询该租户的最新哈希值（per-tenant chain）
            String prevHash = AuditLog.findLatestHash(log.tenantId);
            log.prevHash = prevHash;

            // 计算当前哈希
            StringBuilder content = new StringBuilder();
            if (prevHash != null) {
                content.append(prevHash);
            }
            content.append(log.eventType != null ? log.eventType : "");
            content.append(log.timestamp != null ? log.timestamp.toString() : "");
            content.append(log.tenantId != null ? log.tenantId : "");
            content.append(log.policyModule != null ? log.policyModule : "");
            content.append(log.policyFunction != null ? log.policyFunction : "");
            content.append(log.success != null ? log.success.toString() : "");

            log.currentHash = DigestUtils.sha256Hex(content.toString());

            Log.debugf(
                "Hash chain computed: tenant=%s, prevHash=%s, currentHash=%s",
                log.tenantId,
                prevHash != null ? prevHash.substring(0, 8) + "..." : "null",
                log.currentHash.substring(0, 8) + "..."
            );
        } catch (Exception e) {
            Log.errorf(e, "Failed to compute hash chain for tenant=%s", log.tenantId);
            // 哈希计算失败不影响审计记录持久化
            log.prevHash = null;
            log.currentHash = null;
        }
    }
}
