package com.ailabs.vmadmin.alerts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.FleetStore;
import com.ailabs.vmadmin.metrics.MetricsService;
import com.ailabs.vmadmin.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Raises an alert when a host stays offline, its disk or memory passes a threshold, load per CPU is
 * too high, a systemd unit failed or (optionally) a reboot is pending, and clears it when the condition
 * goes away. Both transitions are audited and, if a webhook is configured, posted to it.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HISTORY = 300;

    public record Alert(String host, String kind, String message, Instant since, Instant resolvedAt) {}

    private final AdminProperties.Alerts cfg;
    private final FleetStore store;
    private final Map<String, Alert> active = new ConcurrentHashMap<>();
    private final Deque<Alert> history = new ArrayDeque<>();
    private final Map<String, Instant> downSince = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AlertService(AdminProperties props, FleetStore store) {
        this.cfg = props.alerts();
        this.store = store;
    }

    public AdminProperties.Alerts config() {
        return cfg;
    }

    public boolean webhookConfigured() {
        return cfg.webhookRef() != null && !cfg.webhookRef().isBlank();
    }

    // ------------------------------------------------------------------ inputs

    @EventListener
    public void onStatus(FleetService.StatusEvent e) {
        FleetService.Status s = e.current();
        String host = s.host();
        if ("online".equals(s.state())) {
            downSince.remove(host);
            resolve(host, "offline");
            return;
        }
        Instant since = downSince.computeIfAbsent(host, k -> Instant.now());
        if (Duration.between(since, Instant.now()).compareTo(cfg.offlineAfter()) >= 0) {
            String what = "unauthorized".equals(s.state()) ? "rejects the control centre's token" : "is " + s.state();
            fire(host, "offline", host + " " + what + (s.message() != null ? ": " + s.message() : ""));
        }
    }

    public void onSample(String host, MetricsService.Sample s) {
        check(host, "disk", s.diskPct() != null && s.diskPct() >= cfg.diskPct(),
                () -> "root disk " + s.diskPct() + "% full (threshold " + cfg.diskPct() + "%)");
        check(host, "memory", s.memPct() != null && s.memPct() >= cfg.memPct(),
                () -> "memory " + s.memPct() + "% used (threshold " + cfg.memPct() + "%)");
        boolean hot = s.load1() != null && s.cpus() != null && s.cpus() > 0 && s.load1() / s.cpus() >= cfg.loadPerCpu();
        check(host, "load", hot, () -> "load " + s.load1() + " on " + s.cpus() + " CPUs (threshold " + cfg.loadPerCpu() + " per CPU)");
        check(host, "failed-units", cfg.failedUnits() && s.failedUnits() != null && s.failedUnits() > 0,
                () -> s.failedUnits() + " failed systemd unit(s)");
        check(host, "reboot", cfg.rebootRequired() && Boolean.TRUE.equals(s.rebootRequired()), () -> "reboot required");
    }

    private void check(String host, String kind, boolean bad, java.util.function.Supplier<String> msg) {
        if (bad) {
            fire(host, kind, host + ": " + msg.get());
        } else {
            resolve(host, kind);
        }
    }

    // ------------------------------------------------------------------ state

    private void fire(String host, String kind, String message) {
        String key = host + "|" + kind;
        Alert prev = active.get(key);
        if (prev != null) {
            active.put(key, new Alert(host, kind, message, prev.since(), null));
            return;
        }
        Alert a = new Alert(host, kind, message, Instant.now(), null);
        active.put(key, a);
        remember(a);
        asAlerts(() -> Audit.log(host, "ALERT", kind + ": " + message));
        send(a, "firing");
    }

    private void resolve(String host, String kind) {
        Alert a = active.remove(host + "|" + kind);
        if (a != null) {
            Alert done = new Alert(host, kind, a.message(), a.since(), Instant.now());
            remember(done);
            asAlerts(() -> Audit.log(host, "ALERT_RESOLVED", kind));
            send(done, "resolved");
        }
    }

    private static void asAlerts(Runnable r) {
        String prev = Audit.actor();
        Audit.actor("alerts");
        try {
            r.run();
        } finally {
            Audit.actor(prev);
        }
    }

    private void remember(Alert a) {
        synchronized (history) {
            history.addFirst(a);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
    }

    public List<Alert> active() {
        List<Alert> out = new ArrayList<>();
        for (Alert a : active.values()) {
            if (store.find(a.host()).isPresent()) {
                out.add(a);
            } else {
                active.remove(a.host() + "|" + a.kind());
            }
        }
        out.sort(Comparator.comparing(Alert::since).reversed());
        return out;
    }

    public List<Alert> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ------------------------------------------------------------------ webhook

    private void send(Alert a, String state) {
        if (!webhookConfigured()) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                post(a, state);
            } catch (RuntimeException e) {
                log.warn("alert webhook failed: {}", e.getMessage());
            }
        });
    }

    /** Send a test message; returns the webhook's HTTP status. */
    public Map<String, Object> test() {
        if (!webhookConfigured()) {
            throw new IllegalArgumentException("No webhook configured (vmadmin.alerts.webhook-ref)");
        }
        int status = post(new Alert("vm-admin", "test", "Test alert from the VM control centre", Instant.now(), null), "test");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("ok", status / 100 == 2);
        return m;
    }

    private int post(Alert a, String state) {
        String url = SecretResolver.bootstrap(cfg.webhookRef(), "vmadmin.alerts.webhook-ref");
        Object body;
        if ("slack".equals(cfg.format())) {
            String icon = "resolved".equals(state) ? ":white_check_mark:" : "test".equals(state) ? ":information_source:" : ":red_circle:";
            body = Map.of("text", icon + " *" + ("resolved".equals(state) ? "Resolved" : "test".equals(state) ? "Test" : "Alert")
                    + "* " + a.message());
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("host", a.host());
            m.put("kind", a.kind());
            m.put("message", a.message());
            m.put("since", a.since().toString());
            m.put("resolvedAt", a.resolvedAt() == null ? null : a.resolvedAt().toString());
            body = m;
        }
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            return http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("webhook unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
