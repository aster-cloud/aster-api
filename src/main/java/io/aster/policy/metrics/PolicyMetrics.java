package io.aster.policy.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 策略评估性能指标
 *
 * 提供以下指标：
 * - policy_evaluation_duration_seconds: 策略评估耗时（直方图，包含 p50/p95/p99）
 * - policy_evaluation_total: 策略评估总次数（计数器）
 * - policy_evaluation_errors_total: 策略评估错误次数（计数器）
 * - active_policy_versions: 活跃策略版本数（仪表）
 * - cache_hit_ratio: 缓存命中率（仪表）
 */
@ApplicationScoped
public class PolicyMetrics {

    @Inject
    MeterRegistry registry;

    // 缓存统计
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // 活跃策略版本数
    private final AtomicInteger activePolicyVersions = new AtomicInteger(0);

    // 业务指标：贷款批准率统计
    private final AtomicLong loanApprovals = new AtomicLong(0);
    private final AtomicLong loanRejections = new AtomicLong(0);

    // 策略模块使用统计
    private final ConcurrentHashMap<String, AtomicLong> policyModuleUsage = new ConcurrentHashMap<>();

    /**
     * 初始化指标
     * 在应用启动时自动调用
     */
    @PostConstruct
    public void init() {
        // 注册缓存命中率 Gauge
        Gauge.builder("cache_hit_ratio", this, PolicyMetrics::calculateCacheHitRatio)
            .description("Policy cache hit ratio")
            .register(registry);

        // 注册活跃策略版本数 Gauge
        Gauge.builder("active_policy_versions", activePolicyVersions, AtomicInteger::get)
            .description("Number of active policy versions")
            .register(registry);

        // 注册贷款批准率 Gauge
        Gauge.builder("loan_approval_rate", this, PolicyMetrics::calculateLoanApprovalRate)
            .description("Loan approval rate")
            .tag("domain", "finance")
            .register(registry);
    }

    /**
     * 记录策略评估时长
     *
     * @param module 策略模块名
     * @param function 策略函数名
     * @param durationMs 执行时长（毫秒）
     * @param success 是否成功
     */
    public void recordEvaluation(String module, String function, long durationMs, boolean success) {
        // 记录评估时长
        Timer.builder("policy_evaluation_duration_seconds")
            .description("Policy evaluation duration in seconds")
            .tag("module", module)
            .tag("function", function)
            .tag("status", success ? "success" : "error")
            .register(registry)
            .record(java.time.Duration.ofMillis(durationMs));

        // 记录评估次数
        Counter.builder("policy_evaluation_total")
            .description("Total number of policy evaluations")
            .tag("module", module)
            .tag("function", function)
            .tag("status", success ? "success" : "error")
            .register(registry)
            .increment();

        // 记录错误次数
        if (!success) {
            Counter.builder("policy_evaluation_errors_total")
                .description("Total number of policy evaluation errors")
                .tag("module", module)
                .tag("function", function)
                .register(registry)
                .increment();
        }

        // 更新模块使用统计
        policyModuleUsage.computeIfAbsent(module, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    /**
     * 计算缓存命中率
     *
     * @return 缓存命中率 (0.0 - 1.0)
     */
    private double calculateCacheHitRatio() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;

        if (total == 0) {
            return 0.0;
        }

        return (double) hits / total;
    }

    /**
     * 设置活跃策略版本数
     *
     * @param count 版本数
     */
    public void setActivePolicyVersions(int count) {
        activePolicyVersions.set(count);
    }

    /**
     * 记录贷款批准
     */
    public void recordLoanApproval() {
        loanApprovals.incrementAndGet();

        Counter.builder("loan_decisions_total")
            .description("Total number of loan decisions")
            .tag("decision", "approved")
            .tag("domain", "finance")
            .register(registry)
            .increment();
    }

    /**
     * 记录贷款拒绝
     */
    public void recordLoanRejection() {
        loanRejections.incrementAndGet();

        Counter.builder("loan_decisions_total")
            .description("Total number of loan decisions")
            .tag("decision", "rejected")
            .tag("domain", "finance")
            .register(registry)
            .increment();
    }

    /**
     * 计算贷款批准率
     *
     * @return 批准率 (0.0 - 1.0)
     */
    private double calculateLoanApprovalRate() {
        long approvals = loanApprovals.get();
        long rejections = loanRejections.get();
        long total = approvals + rejections;

        if (total == 0) {
            return 0.0;
        }

        return (double) approvals / total;
    }

    /**
     * 获取缓存统计信息
     *
     * @return 包含命中数、未命中数和命中率的统计信息
     */
    public CacheStats getCacheStats() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        double ratio = total == 0 ? 0.0 : (double) hits / total;

        return new CacheStats(hits, misses, total, ratio);
    }

    /**
     * 重置缓存统计
     */
    public void resetCacheStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /**
     * 缓存统计记录
     */
    public record CacheStats(
        long hits,
        long misses,
        long total,
        double hitRatio
    ) {}

    /**
     * 重置指定策略的评估指标（测试专用）
     *
     * @param module 策略模块
     * @param function 策略函数
     */
    public void resetEvaluationMetrics(String module, String function) {
        removeMeter(registry.find("policy_evaluation_duration_seconds")
            .tag("module", module)
            .tag("function", function)
            .tag("status", "success")
            .timer());

        removeMeter(registry.find("policy_evaluation_duration_seconds")
            .tag("module", module)
            .tag("function", function)
            .tag("status", "error")
            .timer());

        removeMeter(registry.find("policy_evaluation_total")
            .tag("module", module)
            .tag("function", function)
            .tag("status", "success")
            .counter());

        removeMeter(registry.find("policy_evaluation_total")
            .tag("module", module)
            .tag("function", function)
            .tag("status", "error")
            .counter());

        removeMeter(registry.find("policy_evaluation_errors_total")
            .tag("module", module)
            .tag("function", function)
            .counter());

        policyModuleUsage.computeIfPresent(module, (k, v) -> {
            v.set(0);
            return v;
        });
    }

    private void removeMeter(Meter meter) {
        if (meter != null) {
            registry.remove(meter);
        }
    }
}
