package com.ailabs.canary.chaos;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Component "chaos" of /actuator/health. UP normally; the status set in the console otherwise
 * (DOWN and OUT_OF_SERVICE make /actuator/health answer 503).
 *
 * Only the overall health changes: the liveness and readiness probe groups do not include this component,
 * so Kubernetes does not react. Use the readiness / liveness faults for that.
 */
@Component("chaos")
public class ChaosHealthIndicator implements HealthIndicator {
    private final ChaosService chaos;

    public ChaosHealthIndicator(ChaosService chaos) {
        this.chaos = chaos;
    }

    @Override
    public Health health() {
        Health.Builder b = Health.status(chaos.healthStatus());
        if (!chaos.healthReason().isBlank()) b.withDetail("reason", chaos.healthReason());
        b.withDetail("activeFaults", ChaosService.FAULTS.stream().filter(chaos::isActive).toList());
        return b.build();
    }
}
