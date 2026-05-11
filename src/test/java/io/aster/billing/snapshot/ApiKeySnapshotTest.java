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
        ApiKeySnapshot s = new ApiKeySnapshot(false, "revoked", "k1", "u1", "pro", ts);
        assertThat(s.revoked()).isTrue();
    }

    @Test
    void revoked_returnsFalseWhenNull() {
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k1", "u1", "pro", null);
        assertThat(s.revoked()).isFalse();
    }
}
