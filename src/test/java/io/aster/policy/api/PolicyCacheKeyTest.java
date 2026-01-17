package io.aster.policy.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PolicyCacheKey 单元测试
 * 验证 versionId 字段的正确性
 */
class PolicyCacheKeyTest {

    @Test
    void shouldIncludeVersionIdInHashCode() {
        Object[] context = new Object[]{"test"};

        PolicyCacheKey key1 = new PolicyCacheKey("tenant1", "module", "function", "v1", context);
        PolicyCacheKey key2 = new PolicyCacheKey("tenant1", "module", "function", "v2", context);

        // 不同版本应该有不同的 hashCode
        assertThat(key1.hashCode()).isNotEqualTo(key2.hashCode());
    }

    @Test
    void shouldIncludeVersionIdInEquals() {
        Object[] context = new Object[]{"test"};

        PolicyCacheKey key1 = new PolicyCacheKey("tenant1", "module", "function", "v1", context);
        PolicyCacheKey key2 = new PolicyCacheKey("tenant1", "module", "function", "v2", context);
        PolicyCacheKey key3 = new PolicyCacheKey("tenant1", "module", "function", "v1", context);

        // 不同版本应该不相等
        assertThat(key1).isNotEqualTo(key2);

        // 相同版本应该相等
        assertThat(key1).isEqualTo(key3);
    }

    @Test
    void shouldSupportNullVersionId() {
        Object[] context = new Object[]{"test"};

        PolicyCacheKey key1 = new PolicyCacheKey("tenant1", "module", "function", null, context);
        PolicyCacheKey key2 = new PolicyCacheKey("tenant1", "module", "function", null, context);

        // null versionId 应该相等
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void shouldSupportBackwardCompatibleConstructor() {
        Object[] context = new Object[]{"test"};

        // 使用旧构造函数（不带 versionId）
        PolicyCacheKey key1 = new PolicyCacheKey("tenant1", "module", "function", context);

        // 使用新构造函数（versionId 为 null）
        PolicyCacheKey key2 = new PolicyCacheKey("tenant1", "module", "function", null, context);

        // 应该相等（向后兼容）
        assertThat(key1).isEqualTo(key2);
        assertThat(key1.getVersionId()).isNull();
    }

    @Test
    void shouldIncludeVersionIdInToString() {
        Object[] context = new Object[]{"test"};

        PolicyCacheKey key = new PolicyCacheKey("tenant1", "module", "function", "v123", context);

        String str = key.toString();

        // toString 应该包含 versionId
        assertThat(str).contains("versionId='v123'");
    }

    @Test
    void shouldDifferentiateSameModuleFunctionDifferentVersions() {
        Object[] context = new Object[]{"test"};

        PolicyCacheKey keyV1 = new PolicyCacheKey("tenant1", "aster.finance.loan", "evaluate", "100", context);
        PolicyCacheKey keyV2 = new PolicyCacheKey("tenant1", "aster.finance.loan", "evaluate", "101", context);

        // 相同策略的不同版本应该有不同的缓存键
        assertThat(keyV1).isNotEqualTo(keyV2);
        assertThat(keyV1.hashCode()).isNotEqualTo(keyV2.hashCode());
    }
}
