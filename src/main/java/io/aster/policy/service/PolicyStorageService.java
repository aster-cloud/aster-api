package io.aster.policy.service;

import io.aster.policy.entity.PolicyDocumentEntity;
import io.aster.workflow.DeterminismContext;
import io.aster.api.workflow.PostgresWorkflowRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 策略 CRUD 存储服务（DB-backed）。
 *
 * <p>持久化在 {@code policy_documents} 表（Panache + JSONB）。这是面向 API 的
 * 「策略文档」（name + allow/deny ACL + CNL 文本），租户隔离，dashboard CRUD 可变；
 * 与不可变版本化部署的 {@code policy_versions} / {@code policy_catalog} 职责分明、并存。</p>
 *
 * <p>历史：本服务原为进程内 {@link java.util.concurrent.ConcurrentHashMap} 内存存储，
 * 重启即丢、不跨副本——是 GA blocker。现已改为 DB-backed 持久化，CRUD 契约与
 * {@link PolicyDocument} 值类型保持不变，调用方（{@link io.aster.policy.api.PolicyManagementService}）
 * 无需改动。</p>
 */
@ApplicationScoped
public class PolicyStorageService {

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    /**
     * 列出指定租户的全部策略。
     */
    @Transactional
    public List<PolicyDocument> listPolicies(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        List<PolicyDocumentEntity> entities =
            PolicyDocumentEntity.list("tenantId", normalizedTenant);
        List<PolicyDocument> documents = new ArrayList<>(entities.size());
        for (PolicyDocumentEntity entity : entities) {
            documents.add(toDocument(entity));
        }
        return documents;
    }

    /**
     * 根据 ID 获取策略。
     */
    @Transactional
    public Optional<PolicyDocument> getPolicy(String tenantId, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return Optional.empty();
        }
        return findEntity(normalizeTenant(tenantId), policyId.trim()).map(this::toDocument);
    }

    /**
     * 创建策略，若未提供 ID 则自动生成。
     */
    @Transactional
    public PolicyDocument createPolicy(String tenantId, PolicyDocument document) {
        String normalizedTenant = normalizeTenant(tenantId);
        PolicyDocument toPersist = ensureId(document);
        PolicyDocumentEntity entity = new PolicyDocumentEntity();
        entity.id = toPersist.getId();
        entity.tenantId = normalizedTenant;
        applyDocument(entity, toPersist);
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.persist();
        return toDocument(entity);
    }

    /**
     * 更新策略，若不存在则返回空。
     */
    @Transactional
    public Optional<PolicyDocument> updatePolicy(String tenantId, String policyId, PolicyDocument document) {
        if (policyId == null || policyId.isBlank()) {
            return Optional.empty();
        }
        String normalizedTenant = normalizeTenant(tenantId);
        String sanitizedId = policyId.trim();
        return findEntity(normalizedTenant, sanitizedId).map(entity -> {
            applyDocument(entity, document);
            entity.updatedAt = Instant.now();
            entity.persist();
            return toDocument(entity);
        });
    }

    /**
     * 删除策略。
     */
    @Transactional
    public boolean deletePolicy(String tenantId, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return false;
        }
        return PolicyDocumentEntity.delete(
            "tenantId = ?1 and id = ?2", normalizeTenant(tenantId), policyId.trim()) > 0;
    }

    private Optional<PolicyDocumentEntity> findEntity(String tenantId, String policyId) {
        return PolicyDocumentEntity
            .<PolicyDocumentEntity>find("tenantId = ?1 and id = ?2", tenantId, policyId)
            .firstResultOptional();
    }

    /**
     * 把文档字段写入实体（name / allow / deny / cnl）。id 与 tenantId 由调用点设置。
     */
    private void applyDocument(PolicyDocumentEntity entity, PolicyDocument document) {
        entity.name = document.getName();
        entity.allow = document.getAllow();
        entity.deny = document.getDeny();
        entity.cnl = document.getCnl();
    }

    private PolicyDocument toDocument(PolicyDocumentEntity entity) {
        return new PolicyDocument(entity.id, entity.name, entity.allow, entity.deny, entity.cnl);
    }

    private PolicyDocument ensureId(PolicyDocument document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document;
        }
        return document.withId(generateDeterministicId());
    }

    /**
     * 生成确定性的策略 ID。
     *
     * <p>workflow replay 模式下必须复用 DeterminismContext 的 UUID 门面，
     * 否则相同输入在重放时会产生全新的策略 ID。</p>
     */
    private String generateDeterministicId() {
        DeterminismContext context = workflowRuntime != null ? workflowRuntime.getDeterminismContext() : null;
        if (context != null) {
            return context.uuid().randomUUID().toString();
        }
        return UUID.randomUUID().toString();
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    /**
     * 对外策略文档表示（API 契约值类型，与持久化实体解耦）。
     */
    public static final class PolicyDocument {
        private final String id;
        private final String name;
        private final Map<String, List<String>> allow;
        private final Map<String, List<String>> deny;
        private final String cnl;

        // 向后兼容构造函数（不含 cnl）
        public PolicyDocument(String id, String name, Map<String, List<String>> allow, Map<String, List<String>> deny) {
            this(id, name, allow, deny, null);
        }

        public PolicyDocument(String id, String name, Map<String, List<String>> allow, Map<String, List<String>> deny, String cnl) {
            this.id = id;
            this.name = Objects.requireNonNull(name, "策略名称不能为空");
            this.allow = sanitizeRuleSet(allow);
            this.deny = sanitizeRuleSet(deny);
            this.cnl = cnl;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Map<String, List<String>> getAllow() {
            return deepCopy(allow);
        }

        public Map<String, List<String>> getDeny() {
            return deepCopy(deny);
        }

        public String getCnl() {
            return cnl;
        }

        public PolicyDocument withId(String newId) {
            return new PolicyDocument(newId, this.name, this.allow, this.deny, this.cnl);
        }

        private static Map<String, List<String>> sanitizeRuleSet(Map<String, List<String>> source) {
            Map<String, List<String>> sanitized = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                    if (entry == null) {
                        continue;
                    }
                    String key = entry.getKey();
                    if (key == null || key.trim().isEmpty()) {
                        continue;
                    }
                    List<String> patterns = new ArrayList<>();
                    if (entry.getValue() != null) {
                        for (String pattern : entry.getValue()) {
                            if (pattern != null && !pattern.trim().isEmpty()) {
                                patterns.add(pattern.trim());
                            }
                        }
                    }
                    sanitized.put(key.trim(), Collections.unmodifiableList(new ArrayList<>(patterns)));
                }
            }
            return Collections.unmodifiableMap(sanitized);
        }

        private static Map<String, List<String>> deepCopy(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (source != null) {
                for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                    copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
