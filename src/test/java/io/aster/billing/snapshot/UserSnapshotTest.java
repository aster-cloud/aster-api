package io.aster.billing.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSnapshotTest {

    @Test
    void apiAccessAllowed_freeReturnsFalse() {
        UserSnapshot s = new UserSnapshot("u1", "free", 0, null, null, null);
        assertThat(s.apiAccessAllowed()).isFalse();
    }

    @Test
    void apiAccessAllowed_proReturnsTrue() {
        UserSnapshot s = new UserSnapshot("u1", "pro", 5000, "active", null, null);
        assertThat(s.apiAccessAllowed()).isTrue();
    }

    @Test
    void unlimitedApi_onlyEnterprise() {
        assertThat(new UserSnapshot("u", "enterprise", -1, null, null, null).unlimitedApi()).isTrue();
        assertThat(new UserSnapshot("u", "pro", 5000, null, null, null).unlimitedApi()).isFalse();
    }

    @Test
    void banned_futureReturnsTrue() {
        long inFuture = System.currentTimeMillis() + 10_000;
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, inFuture, null);
        assertThat(s.banned(System.currentTimeMillis())).isTrue();
    }

    @Test
    void banned_pastReturnsFalse() {
        long inPast = System.currentTimeMillis() - 10_000;
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, inPast, null);
        assertThat(s.banned(System.currentTimeMillis())).isFalse();
    }

    @Test
    void banned_nullReturnsFalse() {
        UserSnapshot s = new UserSnapshot("u", "free", 20, null, null, null);
        assertThat(s.banned(System.currentTimeMillis())).isFalse();
    }
}
