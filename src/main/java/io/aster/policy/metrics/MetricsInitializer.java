package io.aster.policy.metrics;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * 指标初始化器
 *
 * 在应用启动时初始化 Micrometer 指标
 */
@ApplicationScoped
public class MetricsInitializer {

    private static final Logger LOG = Logger.getLogger(MetricsInitializer.class);

    @Inject
    PolicyMetrics policyMetrics;

    /**
     * 应用启动时初始化指标
     *
     * @param event 启动事件
     */
    void onStart(@Observes StartupEvent event) {
        LOG.info("Initializing Micrometer metrics...");
        policyMetrics.init();
        LOG.info("Micrometer metrics initialized successfully");
    }
}
