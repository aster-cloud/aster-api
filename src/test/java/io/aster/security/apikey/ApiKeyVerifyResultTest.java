package io.aster.security.apikey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyVerifyResultTest {

    @Test
    void invalid_factory_setsValidFalse() {
        ApiKeyVerifyResult r = ApiKeyVerifyResult.invalid("revoked");
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).isEqualTo("revoked");
        assertThat(r.userId()).isNull();
    }

    @Test
    void valid_factory_setsAllFields() {
        ApiKeyVerifyResult r = ApiKeyVerifyResult.valid("key-1", "user-1", "tenant-1", "pro", "active");
        assertThat(r.valid()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.apiKeyId()).isEqualTo("key-1");
        assertThat(r.userId()).isEqualTo("user-1");
        assertThat(r.tenantId()).isEqualTo("tenant-1");
        assertThat(r.plan()).isEqualTo("pro");
        assertThat(r.subscriptionStatus()).isEqualTo("active");
    }
}
