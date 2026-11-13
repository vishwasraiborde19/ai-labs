package com.ailabs.commandcenter.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code commandcenter:} section of {@code ~/.command-center/config.yml}. The catalog itself
 * (systems, flows) lives in {@code catalog.yml}, edited from the UI.
 */
@ConfigurationProperties(prefix = "commandcenter")
public record CcProperties(Ui ui, Health health, Orchestrator orchestrator) {

    public CcProperties {
        ui = ui != null ? ui : new Ui(null);
        health = health != null ? health : new Health(null, null, null);
        orchestrator = orchestrator != null ? orchestrator : new Orchestrator(null, null, null);
    }

    /**
     * @param editFromLoopbackOnly when the UI listens on the LAN, other machines may browse but only
     *                             this machine may add, change or delete entries (until SSO is added)
     */
    public record Ui(Boolean editFromLoopbackOnly) {
        public Ui {
            editFromLoopbackOnly = editFromLoopbackOnly == null || editFromLoopbackOnly;
        }
    }

    /**
     * @param interval     how often every system is probed
     * @param timeout      per probe
     * @param certWarnDays TLS certificates expiring sooner than this are flagged
     */
    public record Health(Duration interval, Duration timeout, Integer certWarnDays) {
        public Health {
            interval = interval != null ? interval : Duration.ofSeconds(60);
            timeout = timeout != null ? timeout : Duration.ofSeconds(5);
            certWarnDays = certWarnDays != null ? certWarnDays : 21;
        }
    }

    /**
     * The MCP orchestrator, whose agent registry is imported into the catalog.
     *
     * @param url      base URL; its API only answers clients on its own machine
     * @param autoSync import agents in the background
     * @param interval how often
     */
    public record Orchestrator(String url, Boolean autoSync, Duration interval) {
        public Orchestrator {
            url = url == null || url.isBlank() ? "http://127.0.0.1:8770" : url.trim().replaceAll("/+$", "");
            autoSync = autoSync == null || autoSync;
            interval = interval != null ? interval : Duration.ofMinutes(5);
        }
    }

    /** {@code COMMAND_CENTER_HOME}, default {@code ~/.command-center}. */
    public static Path home() {
        String h = System.getenv("COMMAND_CENTER_HOME");
        return h != null && !h.isBlank() ? Path.of(h) : Path.of(System.getProperty("user.home"), ".command-center");
    }
}
