package io.aster.policy.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * 北极星指标（WAADR）后端埋点桥接点
 *
 * 双轨发射：
 *   1. Mixpanel HTTP（异步 + 队列），用于产品分析
 *   2. Prometheus Counter（每事件 +1），用于反指标告警和 Grafana
 *
 * 这样 PromQL 中 rate(rule_rolled_back_total[7d]) / rate(draft_published_total[7d])
 * 即可作为 7 日回滚率监控（详见 aster-api/docs/metrics/MetricsCatalog.md）。
 */
@ApplicationScoped
public class NsmTelemetry {

    private static final Logger LOG = Logger.getLogger(NsmTelemetry.class);

    @Inject
    MixpanelClient mixpanelClient;

    @Inject
    MeterRegistry registry;

    /**
     * 在启动时预注册 NSM counter，让 Prometheus 即便业务事件尚未发生
     * 也能 scrape 到 metric=0。否则 Grafana 仪表盘在新部署后会显示
     * "No data" 直到第一次真实事件发生。
     *
     * tenant_id=unknown 是占位 tag（与 track() 内 fallback 一致）；
     * 真实事件发生时会按真实 tenant 注册新的 series。
     */
    void onStart(@Observes StartupEvent ev) {
        for (String event : new String[]{NsmEvents.DRAFT_PUBLISHED, NsmEvents.RULE_ROLLED_BACK}) {
            Counter.builder(event + "_total")
                .description("NSM event " + event + " (pre-registered)")
                .tag("tenant_id", "unknown")
                .register(registry);
        }
        LOG.info("[NSM] pre-registered draft_published_total + rule_rolled_back_total counters");
    }

    /**
     * 上报一个 NSM 相关事件
     *
     * @param distinctId 用户标识（与 Mixpanel identify 对齐；无值时传租户）
     * @param event      事件名（建议使用 NsmEvents 中的常量）
     * @param properties 事件属性
     */
    public void track(String distinctId, String event, Map<String, Object> properties) {
        if (LOG.isDebugEnabled()) {
            LOG.debugf("[NSM] event=%s distinctId=%s props=%s", event, distinctId, properties);
        }

        // Prometheus Counter — 由事件名直接派生 metric 名（rule_rolled_back → rule_rolled_back_total）
        Object tenantId = properties != null ? properties.get("tenant_id") : null;
        Object sourceKind = properties != null ? properties.get("source_kind") : null;
        Counter.Builder builder = Counter.builder(event + "_total")
            .description("NSM event " + event)
            .tag("tenant_id", tenantId != null ? tenantId.toString() : "unknown");
        if (sourceKind != null) {
            builder.tag("source_kind", sourceKind.toString());
        }
        builder.register(registry).increment();

        mixpanelClient.enqueue(distinctId, event, properties);
    }
}
