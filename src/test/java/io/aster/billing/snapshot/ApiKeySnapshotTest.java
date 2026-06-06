package io.aster.billing.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySnapshotTest {

    @Test
    void invalid_factory_setsValidFalse() {
        ApiKeySnapshot s = ApiKeySnapshot.invalid("revoked");
        assertThat(s.valid()).isFalse();
        assertThat(s.reason()).isEqualTo("revoked");
        assertThat(s.userId()).isNull();
    }

    @Test
    void revoked_returnsTrueWhenSet() {
        long ts = System.currentTimeMillis();
        ApiKeySnapshot s = new ApiKeySnapshot(false, "revoked", "k1", "u1", "tenant-a", "pro", "owner", ts);
        assertThat(s.revoked()).isTrue();
    }

    @Test
    void revoked_returnsFalseWhenNull() {
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null);
        assertThat(s.revoked()).isFalse();
    }

    @Test
    void tenantId_isPreserved() {
        // 租户隔离回归：snapshot 必须保留 key 所属租户，不能丢失或被 userId 顶替。
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "tenant-a", "pro", "owner", null);
        assertThat(s.tenantId()).isEqualTo("tenant-a");
        assertThat(s.userId()).isEqualTo("u1");
        assertThat(s.role()).isEqualTo("owner");
    }
}
