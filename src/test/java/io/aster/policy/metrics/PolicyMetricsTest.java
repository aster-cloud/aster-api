package io.aster.policy.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * PolicyMetrics 测试
 */
@QuarkusTest
class PolicyMetricsTest {

    @Inject
    PolicyMetrics policyMetrics;

    @Inject
    MeterRegistry registry;

    @BeforeEach
    void setUp() {
        // 重置缓存统计
        policyMetrics.resetCacheStats();
        policyMetrics.resetEvaluationMetrics("aster.finance.loan", "evaluateLoanEligibility");
    }

    @Test
    void shouldRecordEvaluationMetrics() {
        // Given
        String module = "aster.finance.loan";
        String function = "evaluateLoanEligibility";
        long duration = 15L;

        // When
        policyMetrics.recordEvaluation(module, function, duration, true);

        // Then
        var timer = registry.find("policy_evaluation_duration_seconds")
            .tag("module", module)
            .tag("function", function)
            .tag("status", "success")
            .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void shouldRecordEvaluationErrors() {
        // Given
        String module = "aster.finance.loan";
        String function = "evaluateLoanEligibility";

        // When
        policyMetrics.recordEvaluation(module, function, 10L, false);

        // Then
        var errorCounter = registry.find("policy_evaluation_errors_total")
            .tag("module", module)
            .tag("function", function)
            .counter();

        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1);
    }

    @Test
    void shouldCalculateCacheHitRatio() {
        // Given
        policyMetrics.recordCacheHit();
        policyMetrics.recordCacheHit();
        policyMetrics.recordCacheHit();
        policyMetrics.recordCacheMiss();

        // When
        var stats = policyMetrics.getCacheStats();

        // Then
        assertThat(stats.hits()).isEqualTo(3);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.hitRatio()).isCloseTo(0.75, within(0.01));
    }

    @Test
    void shouldHandleZeroCacheRequests() {
        // When
        var stats = policyMetrics.getCacheStats();

        // Then
        assertThat(stats.hits()).isEqualTo(0);
        assertThat(stats.misses()).isEqualTo(0);
        assertThat(stats.total()).isEqualTo(0);
        assertThat(stats.hitRatio()).isEqualTo(0.0);
    }

    @Test
    void shouldRecordLoanApprovals() {
        // When
        policyMetrics.recordLoanApproval();
        policyMetrics.recordLoanApproval();

        // Then
        var counter = registry.find("loan_decisions_total")
            .tag("decision", "approved")
            .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2);
    }

    @Test
    void shouldRecordLoanRejections() {
        // When
        policyMetrics.recordLoanRejection();

        // Then
        var counter = registry.find("loan_decisions_total")
            .tag("decision", "rejected")
            .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    void shouldTrackActivePolicyVersions() {
        // When
        policyMetrics.setActivePolicyVersions(5);

        // Then
        var gauge = registry.find("active_policy_versions").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);
    }
}
