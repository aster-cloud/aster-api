package io.aster.policy.security;

import io.aster.policy.entity.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * 安全事件服务
 *
 * 记录所有安全相关事件到数据库
 */
@ApplicationScoped
public class SecurityEventService {

    @ConfigProperty(name = "aster.security.events.enabled", defaultValue = "true")
    boolean eventsEnabled;

    /**
     * 记录签名验证失败事件
     */
    @Transactional
    public void recordSignatureFailure(String tenantId, String reason, Map<String, Object> details) {
        if (!eventsEnabled) {
            return;
        }
        SecurityEvent event = new SecurityEvent(tenantId, "SIGNATURE_FAILURE", "HIGH", details);
        event.persist();
    }

    /**
     * 记录 Nonce 重放攻击事件
     */
    @Transactional
    public void recordNonceReplay(String tenantId, String nonce, Map<String, Object> details) {
        if (!eventsEnabled) {
            return;
        }
        SecurityEvent event = new SecurityEvent(tenantId, "NONCE_REPLAY", "HIGH", details);
        event.persist();
    }

    /**
     * 记录审批拒绝事件
     */
    @Transactional
    public void recordApprovalRejected(String tenantId, Long policyVersionId, String reason) {
        if (!eventsEnabled) {
            return;
        }
        SecurityEvent event = new SecurityEvent(tenantId, "APPROVAL_REJECTED", "MEDIUM",
            Map.of("policyVersionId", policyVersionId, "reason", reason));
        event.policyVersionId = policyVersionId;
        event.persist();
    }

    /**
     * 记录沙箱违规事件
     */
    @Transactional
    public void recordSandboxViolation(String tenantId, Long policyVersionId, String violation) {
        if (!eventsEnabled) {
            return;
        }
        SecurityEvent event = new SecurityEvent(tenantId, "SANDBOX_VIOLATION", "HIGH",
            Map.of("policyVersionId", policyVersionId, "violation", violation));
        event.policyVersionId = policyVersionId;
        event.persist();
    }

    /**
     * 记录哈希不匹配事件
     */
    @Transactional
    public void recordHashMismatch(String tenantId, Long policyVersionId, String expected, String actual) {
        if (!eventsEnabled) {
            return;
        }
        SecurityEvent event = new SecurityEvent(tenantId, "HASH_MISMATCH", "HIGH",
            Map.of("policyVersionId", policyVersionId, "expected", expected, "actual", actual));
        event.policyVersionId = policyVersionId;
        event.persist();
    }
}
