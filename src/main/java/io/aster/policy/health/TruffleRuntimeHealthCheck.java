package io.aster.policy.health;

import io.aster.policy.parser.DynamicCnlExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class TruffleRuntimeHealthCheck implements HealthCheck {

    @Inject
    DynamicCnlExecutor cnlExecutor;

    @Override
    public HealthCheckResponse call() {
        try {
            boolean ready = cnlExecutor != null;
            if (ready) {
                return HealthCheckResponse.up("truffle-runtime");
            }
            return HealthCheckResponse.down("truffle-runtime");
        } catch (Exception e) {
            return HealthCheckResponse.named("truffle-runtime")
                .down()
                .withData("error", e.getMessage())
                .build();
        }
    }
}
