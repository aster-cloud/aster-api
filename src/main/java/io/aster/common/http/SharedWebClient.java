package io.aster.common.http;

import io.vertx.ext.web.client.WebClient;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Application-scoped Vert.x {@link WebClient} provider.
 *
 * <p>P0-R19 (audit R19): consolidates the {@code volatile + synchronized (this) +
 * double-checked} pattern that was duplicated across 7+ services
 * ({@code VertxLlmClient}, {@code SafetyEventReporter}, {@code ApiKeyVerifierService},
 * {@code PlanGateService}, {@code ApiQuotaGuard}, {@code SnapshotWarmupService},
 * {@code MixpanelClient}). The DCL pattern itself was correct (volatile + JMM
 * guarantees), but copy-paste fragmentation is a maintenance hazard and the
 * audit explicitly flagged it.
 *
 * <p>Quarkus {@code @ApplicationScoped} guarantees a single instance per
 * application; the {@code @PostConstruct}-style eager init via the
 * {@link #client()} accessor with a final field replaces 7 ad-hoc DCLs with one
 * shared, well-tested allocation point.
 *
 * <p>Callers inject {@code SharedWebClient} and use {@code shared.client()}
 * instead of holding their own field.
 */
@ApplicationScoped
public class SharedWebClient {

    @Inject
    Vertx mutinyVertx;

    private volatile WebClient client;

    /**
     * Lazy-init the shared {@link WebClient}.
     *
     * <p>{@code volatile} + DCL: same correctness guarantees as the inlined
     * pattern, now centralized. Single allocation point makes lifecycle and
     * future configuration (timeouts, proxy, TLS) easier to manage.
     */
    public WebClient client() {
        WebClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = WebClient.create(mutinyVertx.getDelegate());
                    client = local;
                }
            }
        }
        return local;
    }
}
