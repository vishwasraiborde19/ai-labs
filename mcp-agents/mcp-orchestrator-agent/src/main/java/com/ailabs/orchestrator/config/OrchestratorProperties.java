package com.ailabs.orchestrator.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(String home, Registration registration, Discovery discovery, Limits limits) {

    public OrchestratorProperties {
        if (home == null || home.isBlank()) home = System.getProperty("user.home") + "/.orchestrator";
        if (registration == null) registration = new Registration(null);
        if (discovery == null) discovery = new Discovery(List.of());
        if (limits == null) limits = new Limits(0, 0, null, 0, 0, 0, null);
    }

    public Path homeDir() {
        return Path.of(home.startsWith("~/") ? System.getProperty("user.home") + home.substring(1) : home);
    }

    /** Self-registration of agents: {@code POST /registry/register} with this bearer token. */
    public record Registration(String tokenRef) {
        public boolean enabled() {
            return tokenRef != null && !tokenRef.isBlank();
        }
    }

    /** MCP endpoints probed on every refresh. */
    public record Discovery(List<Endpoint> urls) {
        public Discovery {
            if (urls == null) urls = List.of();
        }
    }

    public record Endpoint(String url, String tokenRef) {}

    /** @param systemsHealthMs how often each system's health tool (e.g. k8s_whoami) runs */
    public record Limits(long refreshMs, long systemsHealthMs, Duration toolTimeout, int maxTurns, int maxToolResultChars,
                         int maxRuns, Duration approvalTimeout) {
        public Limits {
            if (refreshMs <= 0) refreshMs = 60_000;
            if (systemsHealthMs <= 0) systemsHealthMs = 300_000;
            if (toolTimeout == null) toolTimeout = Duration.ofMinutes(10);
            if (maxTurns <= 0) maxTurns = 30;
            if (maxToolResultChars <= 0) maxToolResultChars = 60_000;
            if (maxRuns <= 0) maxRuns = 300;
            if (approvalTimeout == null) approvalTimeout = Duration.ofMinutes(30);
        }
    }
}
