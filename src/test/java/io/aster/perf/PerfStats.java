package io.aster.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 性能统计计算工具，统一输出均值与分位点，避免各基准重复实现。
 */
public final class PerfStats {

    private PerfStats() {
    }

    public static Summary summarize(String metric, List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("样本不能为空: " + metric);
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return new Summary(
            metric,
            mean(samples),
            percentile(sorted, 0.95),
            percentile(sorted, 0.99),
            sorted.get(0),
            sorted.get(sorted.size() - 1)
        );
    }

    public static double mean(List<Double> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : samples) {
            sum += value;
        }
        return sum / samples.size();
    }

    private static double percentile(List<Double> sortedSamples, double percentile) {
        if (sortedSamples.isEmpty()) {
            return 0.0;
        }
        double rank = percentile * (sortedSamples.size() - 1);
        int lowIndex = (int) Math.floor(rank);
        int highIndex = (int) Math.ceil(rank);
        if (lowIndex == highIndex) {
            return sortedSamples.get(lowIndex);
        }
        double weight = rank - lowIndex;
        return sortedSamples.get(lowIndex) * (1 - weight) + sortedSamples.get(highIndex) * weight;
    }

    /**
     * 统一的统计输出结构，字段单位均为毫秒。
     */
    public record Summary(
        String metric,
        double meanMs,
        double p95Ms,
        double p99Ms,
        double minMs,
        double maxMs
    ) {
    }
}
