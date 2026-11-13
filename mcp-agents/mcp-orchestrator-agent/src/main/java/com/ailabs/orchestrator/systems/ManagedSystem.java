package com.ailabs.orchestrator.systems;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A system one agent can reach through its tools, as that agent reports it.
 *
 * @param key          identity across agents: kind plus endpoint (or name), so two agents that reach the same
 *                     cluster share a key
 * @param status       ok, error, unknown, or the live state the list reported (online, offline...)
 * @param statusDetail e.g. Kubernetes version and identity from the health check, or the error
 */
public record ManagedSystem(String key, String kind, String name, String agent, String endpoint, Boolean readOnly,
                            Map<String, Object> details, String status, String statusDetail, Instant checkedAt,
                            List<SystemProbe.QuickAction> actions) {

    ManagedSystem withHealth(String status, String detail) {
        return new ManagedSystem(key, kind, name, agent, endpoint, readOnly, details, status, detail, Instant.now(), actions);
    }
}
