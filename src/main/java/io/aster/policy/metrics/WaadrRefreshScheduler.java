package io.aster.policy.metrics;

import io.quarkus.scheduler.Scheduled;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * 周一 00:30 刷新 pm_weekly_waadr 物化视图
 *
 * REFRESH MATERIALIZED VIEW CONCURRENTLY 不阻塞读取，依赖唯一索引（V6.8.0 已创建）。
 * 失败仅记录日志，下一次 cron 自动重试，不抛出。
 */
@ApplicationScoped
public class WaadrRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(WaadrRefreshScheduler.class);

    @Inject
    EntityManager entityManager;

    @Scheduled(
        cron = "0 30 0 ? * MON",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "waadr-weekly-refresh",
        skipExecutionIf = BackgroundSchedulerSkipPredicate.class
    )
    @Transactional
    public void refreshWeekly() {
        long start = System.currentTimeMillis();
        try {
            entityManager
                .createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY pm_weekly_waadr")
                .executeUpdate();
            long durationMs = System.currentTimeMillis() - start;
            LOG.infof("WAADR 视图刷新完成，耗时 %d ms", durationMs);
        } catch (RuntimeException e) {
            LOG.errorf(e, "WAADR 视图刷新失败：%s", e.getMessage());
        }
    }
}
