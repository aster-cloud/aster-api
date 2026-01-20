package io.aster.policy.performance;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略评估性能基线测试，评估缓存命中前后延迟。
 */
@QuarkusTest
class PolicyEvaluationPerformanceTest {

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationPerformanceTest.class);

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 200;

    @Inject
    PolicyEvaluationService policyEvaluationService;

    /**
     * 创建贷款申请上下文（使用 Map 替代 DTO）
     */
    private Object[] loanContext() {
        Map<String, Object> application = Map.of(
            "applicationId", "APP-PERF-1001",
            "requestedAmount", 250_000,
            "loanTermMonths", 84,
            "purpose", "购房"
        );
        Map<String, Object> applicant = Map.of(
            "age", 37,
            "creditScore", 760,
            "annualIncome", 520_000,
            "monthlyDebt", 6_500,
            "employmentYears", 7
        );
        return new Object[]{application, applicant};
    }

    @BeforeEach
    void clearCache() {
        policyEvaluationService.invalidateCache("default", null, null).await().indefinitely();
    }

    @Test
    void measureLoanEvaluationLatency() {
        Object[] context = loanContext();

        // 首次调用：冷启动测量
        long coldStartNanos = System.nanoTime();
        PolicyEvaluationResult coldResult = policyEvaluationService
            .evaluatePolicy("default", "aster.finance.loan", "evaluateLoanEligibility", context)
            .await().indefinitely();
        long coldDurationNanos = System.nanoTime() - coldStartNanos;

        assertThat(coldResult.getResult()).isNotNull();
        assertThat(coldResult.isFromCache()).isFalse();

        // 预热阶段：填充缓存
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            policyEvaluationService
                .evaluatePolicy("default", "aster.finance.loan", "evaluateLoanEligibility", context)
                .await().indefinitely();
        }

        // 缓存命中阶段测量
        long startHotNanos = System.nanoTime();
        PolicyEvaluationResult lastHotResult = null;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            lastHotResult = policyEvaluationService
                .evaluatePolicy("default", "aster.finance.loan", "evaluateLoanEligibility", context)
                .await().indefinitely();
        }
        long hotDurationNanos = System.nanoTime() - startHotNanos;

        assertThat(lastHotResult).isNotNull();
        assertThat(lastHotResult.isFromCache()).isTrue();

        double coldMs = coldDurationNanos / 1_000_000.0;
        double avgHotMs = (hotDurationNanos / 1_000_000.0) / MEASURE_ITERATIONS;

        LOG.infof(
            "PolicyEvaluationPerformanceTest - LoanEvaluation cold=%.3fms, avgHot=%.3fms (iterations=%d)",
            coldMs,
            avgHotMs,
            MEASURE_ITERATIONS
        );
    }
}
