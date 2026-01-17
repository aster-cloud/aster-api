package io.aster.policy.api;

import io.aster.policy.graphql.types.PolicyTypes;
import io.aster.policy.service.PolicyStorageService;
import io.aster.policy.service.PolicyStorageService.PolicyDocument;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PolicyManagementService 单元测试，覆盖策略 CRUD、类型转换与缓存协同。
 */
@ExtendWith(MockitoExtension.class)
class PolicyManagementServiceTest {

    private PolicyManagementService policyManagementService;

    @Mock
    private PolicyStorageService policyStorageService;

    @Mock
    private CacheManagementService cacheManagementService;

    @BeforeEach
    void setUp() {
        policyManagementService = new PolicyManagementService(policyStorageService, cacheManagementService);
    }

    @Test
    void testCreatePolicy_Success() {
        // Given
        PolicyTypes.PolicyInput input = createPolicyInput("test-policy", "Test Policy");
        PolicyDocument expectedDoc = new PolicyDocument("test-policy", "Test Policy", Collections.emptyMap(), Collections.emptyMap());

        when(policyStorageService.createPolicy(eq("default"), any(PolicyDocument.class)))
            .thenReturn(expectedDoc);
        when(cacheManagementService.invalidateTenantCache("default"))
            .thenReturn(Uni.createFrom().item(new CacheManagementService.CacheOperationResult(true, "ok", System.currentTimeMillis())));

        // When
        Uni<PolicyTypes.Policy> result = policyManagementService.createPolicy("default", input);

        // Then
        PolicyTypes.Policy policy = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policy).isNotNull();
        assertThat(policy.id).isEqualTo("test-policy");
        assertThat(policy.name).isEqualTo("Test Policy");
        verify(cacheManagementService).invalidateTenantCache("default");
    }

    @Test
    void testCreatePolicy_NullInput_ThrowsException() {
        // When & Then
        assertThatThrownBy(() -> policyManagementService.convertToDocument(null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("策略输入不能为空");
    }

    @Test
    void testUpdatePolicy_Success() {
        // Given
        PolicyTypes.PolicyInput input = createPolicyInput("test-policy", "Updated Policy");
        PolicyDocument updatedDoc = new PolicyDocument("test-policy", "Updated Policy", Collections.emptyMap(), Collections.emptyMap());

        when(policyStorageService.updatePolicy(eq("default"), eq("test-policy"), any(PolicyDocument.class)))
            .thenReturn(Optional.of(updatedDoc));
        when(cacheManagementService.invalidateTenantCache("default"))
            .thenReturn(Uni.createFrom().item(new CacheManagementService.CacheOperationResult(true, "ok", System.currentTimeMillis())));

        // When
        Uni<PolicyTypes.Policy> result = policyManagementService.updatePolicy("default", "test-policy", input);

        // Then
        PolicyTypes.Policy policy = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policy).isNotNull();
        assertThat(policy.name).isEqualTo("Updated Policy");
        verify(cacheManagementService).invalidateTenantCache("default");
    }

    @Test
    void testCreatePolicy_CacheInvalidationFails() {
        // Given
        PolicyTypes.PolicyInput input = createPolicyInput("test-policy", "Test Policy");
        PolicyDocument expectedDoc = new PolicyDocument("test-policy", "Test Policy", Collections.emptyMap(), Collections.emptyMap());

        when(policyStorageService.createPolicy(eq("default"), any(PolicyDocument.class)))
            .thenReturn(expectedDoc);
        when(cacheManagementService.invalidateTenantCache("default"))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("cache failure")));

        // When
        UniAssertSubscriber<PolicyTypes.Policy> subscriber = policyManagementService.createPolicy("default", input)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("cache failure");
    }

    @Test
    void testUpdatePolicy_StorageThrows() {
        // Given
        PolicyTypes.PolicyInput input = createPolicyInput("test-policy", "Updated Policy");

        when(policyStorageService.updatePolicy(eq("default"), eq("test-policy"), any(PolicyDocument.class)))
            .thenThrow(new RuntimeException("storage failure"));

        // When
        UniAssertSubscriber<PolicyTypes.Policy> subscriber = policyManagementService.updatePolicy("default", "test-policy", input)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("storage failure");
        verify(cacheManagementService, never()).invalidateTenantCache(anyString());
    }

    @Test
    void testDeletePolicy_CacheInvalidationFails() {
        // Given
        when(policyStorageService.deletePolicy("default", "test-policy"))
            .thenReturn(true);
        when(cacheManagementService.invalidateTenantCache("default"))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("invalidate error")));

        // When
        UniAssertSubscriber<Boolean> subscriber = policyManagementService.deletePolicy("default", "test-policy")
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalidate error");
    }

    @Test
    void testUpdatePolicy_NotFound_ReturnsNull() {
        // Given
        PolicyTypes.PolicyInput input = createPolicyInput("missing", "Missing Policy");

        when(policyStorageService.updatePolicy(eq("default"), eq("missing"), any(PolicyDocument.class)))
            .thenReturn(Optional.empty());

        // When
        Uni<PolicyTypes.Policy> result = policyManagementService.updatePolicy("default", "missing", input);

        // Then
        PolicyTypes.Policy policy = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policy).isNull();
        verify(cacheManagementService, never()).invalidateTenantCache(anyString());
    }

    @Test
    void testDeletePolicy_Success() {
        // Given
        when(policyStorageService.deletePolicy("default", "test-policy"))
            .thenReturn(true);
        when(cacheManagementService.invalidateTenantCache("default"))
            .thenReturn(Uni.createFrom().item(new CacheManagementService.CacheOperationResult(true, "ok", System.currentTimeMillis())));

        // When
        Uni<Boolean> result = policyManagementService.deletePolicy("default", "test-policy");

        // Then
        Boolean deleted = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(deleted).isTrue();
        verify(cacheManagementService).invalidateTenantCache("default");
    }

    @Test
    void testDeletePolicy_NotFound_NoInvalidation() {
        // Given
        when(policyStorageService.deletePolicy("default", "missing"))
            .thenReturn(false);

        // When
        Uni<Boolean> result = policyManagementService.deletePolicy("default", "missing");

        // Then
        Boolean deleted = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(deleted).isFalse();
        verify(cacheManagementService, never()).invalidateTenantCache(anyString());
    }

    @Test
    void testGetPolicy_Found() {
        // Given
        PolicyDocument doc = new PolicyDocument("test-policy", "Test Policy", Collections.emptyMap(), Collections.emptyMap());
        when(policyStorageService.getPolicy("default", "test-policy"))
            .thenReturn(Optional.of(doc));

        // When
        Uni<PolicyTypes.Policy> result = policyManagementService.getPolicy("default", "test-policy");

        // Then
        PolicyTypes.Policy policy = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policy).isNotNull();
        assertThat(policy.id).isEqualTo("test-policy");
    }

    @Test
    void testGetPolicy_NotFound() {
        // Given
        when(policyStorageService.getPolicy("default", "missing"))
            .thenReturn(Optional.empty());

        // When
        Uni<PolicyTypes.Policy> result = policyManagementService.getPolicy("default", "missing");

        // Then
        PolicyTypes.Policy policy = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policy).isNull();
    }

    @Test
    void testListPolicies_MultipleDocuments() {
        // Given
        PolicyDocument doc1 = new PolicyDocument("policy-1", "Policy 1", Collections.emptyMap(), Collections.emptyMap());
        PolicyDocument doc2 = new PolicyDocument("policy-2", "Policy 2", Collections.emptyMap(), Collections.emptyMap());
        when(policyStorageService.listPolicies("default"))
            .thenReturn(Arrays.asList(doc1, doc2));

        // When
        Uni<List<PolicyTypes.Policy>> result = policyManagementService.listPolicies("default");

        // Then
        List<PolicyTypes.Policy> policies = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policies).hasSize(2);
        assertThat(policies.get(0).id).isEqualTo("policy-1");
        assertThat(policies.get(1).id).isEqualTo("policy-2");
    }

    @Test
    void testListPolicies_EmptyList() {
        // Given
        when(policyStorageService.listPolicies("default"))
            .thenReturn(Collections.emptyList());

        // When
        Uni<List<PolicyTypes.Policy>> result = policyManagementService.listPolicies("default");

        // Then
        List<PolicyTypes.Policy> policies = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(policies).isEmpty();
    }

    @Test
    void testConvertToDocument_WithRules() {
        // Given
        PolicyTypes.PolicyInput input = new PolicyTypes.PolicyInput();
        input.id = "policy-1";
        input.name = "Policy with Rules";
        input.allow = createRuleSetInput("files", Arrays.asList("/tmp/*", "/data/*"));
        input.deny = createRuleSetInput("network", Arrays.asList("*.evil.com"));

        // When
        PolicyDocument doc = policyManagementService.convertToDocument("policy-1", input);

        // Then
        assertThat(doc.getId()).isEqualTo("policy-1");
        assertThat(doc.getName()).isEqualTo("Policy with Rules");
        assertThat(doc.getAllow()).containsKey("files");
        assertThat(doc.getAllow().get("files")).containsExactly("/tmp/*", "/data/*");
        assertThat(doc.getDeny()).containsKey("network");
        assertThat(doc.getDeny().get("network")).containsExactly("*.evil.com");
    }

    @Test
    void testConvertFromDocument_WithRules() {
        // Given
        Map<String, List<String>> allow = new LinkedHashMap<>();
        allow.put("files", Arrays.asList("/tmp/*", "/data/*"));
        Map<String, List<String>> deny = new LinkedHashMap<>();
        deny.put("network", Arrays.asList("*.evil.com"));
        PolicyDocument doc = new PolicyDocument("policy-1", "Test Policy", allow, deny);

        // When
        PolicyTypes.Policy policy = policyManagementService.convertFromDocument(doc);

        // Then
        assertThat(policy.id).isEqualTo("policy-1");
        assertThat(policy.name).isEqualTo("Test Policy");
        assertThat(policy.allow.rules).hasSize(1);
        assertThat(policy.allow.rules.get(0).resourceType).isEqualTo("files");
        assertThat(policy.allow.rules.get(0).patterns).containsExactly("/tmp/*", "/data/*");
        assertThat(policy.deny.rules).hasSize(1);
        assertThat(policy.deny.rules.get(0).resourceType).isEqualTo("network");
    }

    // Helper methods

    private PolicyTypes.PolicyInput createPolicyInput(String id, String name) {
        PolicyTypes.PolicyInput input = new PolicyTypes.PolicyInput();
        input.id = id;
        input.name = name;
        input.allow = createRuleSetInput("files", Arrays.asList("/tmp/*"));
        input.deny = new PolicyTypes.PolicyRuleSetInput();
        input.deny.rules = Collections.emptyList();
        return input;
    }

    private PolicyTypes.PolicyRuleSetInput createRuleSetInput(String resourceType, List<String> patterns) {
        PolicyTypes.PolicyRuleSetInput ruleSet = new PolicyTypes.PolicyRuleSetInput();
        PolicyTypes.PolicyRuleInput rule = new PolicyTypes.PolicyRuleInput();
        rule.resourceType = resourceType;
        rule.patterns = patterns;
        ruleSet.rules = Collections.singletonList(rule);
        return ruleSet;
    }
}
