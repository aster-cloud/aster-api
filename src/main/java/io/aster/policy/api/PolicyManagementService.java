package io.aster.policy.api;

import io.aster.policy.graphql.types.PolicyTypes;
import io.aster.policy.service.PolicyStorageService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 策略管理服务，封装策略文档的增删改查、GraphQL类型转换与缓存协同。
 */
@ApplicationScoped
public class PolicyManagementService {

    private static final Logger LOG = Logger.getLogger(PolicyManagementService.class);

    @Inject
    PolicyStorageService policyStorageService;

    @Inject
    CacheManagementService cacheManagementService;

    // 无参构造器供CDI使用
    public PolicyManagementService() {
    }

    // 包私有构造器供测试使用
    PolicyManagementService(PolicyStorageService policyStorageService, CacheManagementService cacheManagementService) {
        this.policyStorageService = policyStorageService;
        this.cacheManagementService = cacheManagementService;
    }

    /**
     * 创建策略文档并返回GraphQL类型。
     */
    public Uni<PolicyTypes.Policy> createPolicy(String tenantId, PolicyTypes.PolicyInput input) {
        String normalizedTenant = normalizeTenant(tenantId);
        return Uni.createFrom().item(() ->
            policyStorageService.createPolicy(normalizedTenant, convertToDocument(null, input))
        ).onItem().transform(this::convertFromDocument)
         .call(created -> cacheManagementService.invalidateTenantCache(normalizedTenant).replaceWithVoid());
    }

    /**
     * 更新策略文档并返回GraphQL类型。
     */
    public Uni<PolicyTypes.Policy> updatePolicy(String tenantId, String id, PolicyTypes.PolicyInput input) {
        String normalizedTenant = normalizeTenant(tenantId);
        String sanitizedId = sanitize(id);
        return Uni.createFrom().item(() ->
            policyStorageService.updatePolicy(
                normalizedTenant,
                sanitizedId,
                convertToDocument(sanitizedId, input)
            )
        ).call(optional -> optional.isPresent()
            ? cacheManagementService.invalidateTenantCache(normalizedTenant).replaceWithVoid()
            : Uni.createFrom().voidItem())
         .onItem().transform(optional -> optional.map(this::convertFromDocument).orElse(null));
    }

    /**
     * 删除策略文档。
     */
    public Uni<Boolean> deletePolicy(String tenantId, String id) {
        String normalizedTenant = normalizeTenant(tenantId);
        String sanitizedId = sanitize(id);
        return Uni.createFrom().item(() ->
            policyStorageService.deletePolicy(normalizedTenant, sanitizedId)
        ).call(deleted -> Boolean.TRUE.equals(deleted)
            ? cacheManagementService.invalidateTenantCache(normalizedTenant).replaceWithVoid()
            : Uni.createFrom().voidItem());
    }

    /**
     * 获取单个策略文档。
     */
    public Uni<PolicyTypes.Policy> getPolicy(String tenantId, String id) {
        String normalizedTenant = normalizeTenant(tenantId);
        return Uni.createFrom().item(() ->
            policyStorageService.getPolicy(normalizedTenant, sanitize(id))
                .map(this::convertFromDocument)
                .orElse(null)
        );
    }

    /**
     * 列出当前租户所有策略。
     */
    public Uni<List<PolicyTypes.Policy>> listPolicies(String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        return Uni.createFrom().item(() -> {
            List<PolicyStorageService.PolicyDocument> documents = policyStorageService.listPolicies(normalizedTenant);
            List<PolicyTypes.Policy> policies = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                PolicyStorageService.PolicyDocument document = documents.get(i);
                if (document == null) {
                    LOG.warnf("[listPolicies] 跳过空文档: tenant=%s index=%d", normalizedTenant, i);
                    continue;
                }
                try {
                    policies.add(convertFromDocument(document));
                } catch (Exception e) {
                    LOG.warnf(e, "[listPolicies] 转换策略失败, 已跳过: tenant=%s id=%s name=%s",
                        normalizedTenant, safe(document.getId()), safe(document.getName()));
                }
            }
            return policies;
        });
    }

    /**
     * 将GraphQL输入转换为策略文档。
     */
    public PolicyStorageService.PolicyDocument convertToDocument(String id, PolicyTypes.PolicyInput input) {
        if (input == null) {
            throw new IllegalArgumentException("策略输入不能为空");
        }

        String name = Objects.requireNonNull(input.name, "策略名称不能为空");
        Map<String, List<String>> allow = convertRuleSetInput(input.allow);
        Map<String, List<String>> deny = convertRuleSetInput(input.deny);
        String effectiveId = id != null ? id : sanitize(input.id);
        return new PolicyStorageService.PolicyDocument(effectiveId, name, allow, deny, input.cnl);
    }

    /**
     * 将策略文档转换为GraphQL输出。
     */
    public PolicyTypes.Policy convertFromDocument(PolicyStorageService.PolicyDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("策略文档不能为空");
        }
        String id = document.getId() == null ? "" : document.getId();
        String name = document.getName() == null ? "" : document.getName();
        Map<String, List<String>> allow = document.getAllow() == null ? new LinkedHashMap<>() : document.getAllow();
        Map<String, List<String>> deny = document.getDeny() == null ? new LinkedHashMap<>() : document.getDeny();
        return new PolicyTypes.Policy(id, name, convertToGraphQLRuleSet(allow), convertToGraphQLRuleSet(deny), document.getCnl());
    }

    private Map<String, List<String>> convertRuleSetInput(PolicyTypes.PolicyRuleSetInput input) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (input == null || input.rules == null) {
            return result;
        }
        for (PolicyTypes.PolicyRuleInput ruleInput : input.rules) {
            if (ruleInput == null || ruleInput.resourceType == null || ruleInput.resourceType.isBlank()) {
                continue;
            }
            String resourceType = ruleInput.resourceType.trim();
            List<String> patterns = new ArrayList<>();
            if (ruleInput.patterns != null) {
                for (String pattern : ruleInput.patterns) {
                    if (pattern != null && !pattern.trim().isEmpty()) {
                        patterns.add(pattern.trim());
                    }
                }
            }
            result.put(resourceType, patterns);
        }
        return result;
    }

    private PolicyTypes.PolicyRuleSet convertToGraphQLRuleSet(Map<String, List<String>> rules) {
        List<PolicyTypes.PolicyRule> gqlRules = new ArrayList<>();
        if (rules != null) {
            for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
                String resourceType = entry.getKey() == null ? "" : entry.getKey();
                List<String> patterns = entry.getValue() == null ? new ArrayList<>() : entry.getValue();
                gqlRules.add(new PolicyTypes.PolicyRule(resourceType, patterns));
            }
        }
        return new PolicyTypes.PolicyRuleSet(gqlRules);
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }

    private static String safe(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }
}

