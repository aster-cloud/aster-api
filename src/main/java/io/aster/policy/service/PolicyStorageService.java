package io.aster.policy.service;

import io.aster.workflow.DeterminismContext;
import io.aster.workflow.PostgresWorkflowRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 策略持久化服务，当前使用内存存储实现。
 *
 * <p>支持多租户隔离，后续可以替换为数据库或远程存储。</p>
 */
@ApplicationScoped
public class PolicyStorageService {

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    private final ConcurrentMap<String, ConcurrentMap<String, PolicyDocument>> store = new ConcurrentHashMap<>();

    /**
     * 列出指定租户的全部策略。
     */
    public List<PolicyDocument> listPolicies(String tenantId) {
        return new ArrayList<>(tenantStore(tenantId).values());
    }

    /**
     * 根据 ID 获取策略。
     */
    public Optional<PolicyDocument> getPolicy(String tenantId, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantStore(tenantId).get(policyId));
    }

    /**
     * 创建策略，若未提供 ID 则自动生成。
     */
    public PolicyDocument createPolicy(String tenantId, PolicyDocument document) {
        PolicyDocument toPersist = ensureId(document);
        tenantStore(tenantId).put(toPersist.getId(), toPersist);
        return toPersist;
    }

    /**
     * 更新策略，若不存在则返回空。
     */
    public Optional<PolicyDocument> updatePolicy(String tenantId, String policyId, PolicyDocument document) {
        if (policyId == null || policyId.isBlank()) {
            return Optional.empty();
        }
        ConcurrentMap<String, PolicyDocument> tenantPolicies = tenantStore(tenantId);
        return Optional.ofNullable(tenantPolicies.computeIfPresent(policyId, (id, existing) -> document.withId(policyId)));
    }

    /**
     * 删除策略。
     */
    public boolean deletePolicy(String tenantId, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return false;
        }
        return tenantStore(tenantId).remove(policyId) != null;
    }

    private PolicyDocument ensureId(PolicyDocument document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document;
        }
        return document.withId(generateDeterministicId());
    }

    /**
     * 生成确定性的策略 ID
     *
     * workflow replay 模式下必须复用 DeterminismContext 的 UUID 门面，
     * 否则相同输入在重放时会产生全新的策略 ID。
     */
    private String generateDeterministicId() {
        DeterminismContext context = workflowRuntime != null ? workflowRuntime.getDeterminismContext() : null;
        if (context != null) {
            return context.uuid().randomUUID().toString();
        }
        return UUID.randomUUID().toString();
    }

    private ConcurrentMap<String, PolicyDocument> tenantStore(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        return store.computeIfAbsent(normalizedTenant, key -> new ConcurrentHashMap<>());
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    /**
     * 内部策略文档表示。
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
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
