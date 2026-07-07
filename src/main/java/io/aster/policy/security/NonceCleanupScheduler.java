package io.aster.policy.security;

import io.quarkus.scheduler.Scheduled;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NonceCleanupScheduler {

    private static final Logger LOG = Logger.getLogger(NonceCleanupScheduler.class);

    @Inject
    NonceService nonceService;

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
               skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    void cleanupExpiredNonces() {
        long deleted = nonceService.evictExpired();
        if (deleted > 0) {
            LOG.infof("Cleaned up %d expired nonces", deleted);
        }
    }
}
