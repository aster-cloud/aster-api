package io.aster.llm.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * LLM 用量度量
 *
 * 反指标"LLM 成本/采纳草稿"的源数据：
 *   llm_tokens_total{model, kind=prompt|completion}
 *
 * Prometheus 计算成本：sum(llm_tokens_total{kind="completion"}) * cost_per_1k / 1000
 * 与 pm_weekly_waadr 联动可得"每个采纳草稿的 LLM 成本"。
 *
 * 注意：流式响应可能不带 usage 字段，调用方应在非流式分支调用此服务。
 */
@ApplicationScoped
public class LlmMetrics {

    @Inject
    MeterRegistry registry;

    public void recordTokens(String model, int promptTokens, int completionTokens) {
        if (model == null || model.isBlank()) return;
        if (promptTokens > 0) {
            Counter.builder("llm_tokens_total")
                .tag("model", model)
                .tag("kind", "prompt")
                .register(registry)
                .increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder("llm_tokens_total")
                .tag("model", model)
                .tag("kind", "completion")
                .register(registry)
                .increment(completionTokens);
        }
    }
}
