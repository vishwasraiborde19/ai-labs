package com.ailabs.vmadmin.fleet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.agent.AgentClient;
import com.ailabs.vmadmin.agent.AgentException;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.secrets.SecretResolver;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * The fleet at run time: one {@link AgentClient} per host, a status cache refreshed in the
 * background, parallel fan-out, and pushing the bundled agent version to VMs.
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    /** Collected in one round trip by {@link #sysInfo}; each line is {@code key=value}. */
    static final String SYSINFO_SCRIPT = String.join("; ",
            "echo hostname=$(hostname)",
            "echo os=$(. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\")",
            "echo kernel=$(uname -r)",
            "echo arch=$(uname -m)",
            "echo cpus=$(nproc)",
            "echo uptime_s=$(cut -d. -f1 /proc/uptime)",
            "echo load=$(cut -d' ' -f1-3 /proc/loadavg)",
            "echo mem_total_kb=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)",
            "echo mem_avail_kb=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)",
            "echo swap_total_kb=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)",
            "echo swap_free_kb=$(awk '/^SwapFree:/{print $2}' /proc/meminfo)",
            "echo disk_root=$(df -Pk / | awk 'NR==2{print $2\" \"$3\" \"$4}')",
            "echo ips=$(hostname -I 2>/dev/null)",
            "echo failed_units=$(systemctl --failed --no-legend 2>/dev/null | wc -l)",
            "echo user=$(id -un)",
            "echo reboot_required=$([ -f /var/run/reboot-required ] && echo yes || echo no)");

    public record Status(String host, String state, String version, List<String> features, Long uptimeS,
                         Long latencyMs, Instant checkedAt, String message, String defaultCwd, String base,
                         String agentName) {
        static Status unknown(String host) {
            return new Status(host, "unknown", null, List.of(), null, null, null, "not checked yet", null, null, null);
        }

        public boolean hasFiles() {
            return features != null && features.contains("fs");
        }
    }

    private record CachedClient(Host host, AgentClient client) {}

    private record CachedToken(String ref, String value, Instant expires) {}

    private final FleetStore store;
    private final SecretResolver secrets;
    private final AdminProperties props;
    private final ApplicationEventPublisher events;
    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Status> status = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fleet-status");
        t.setDaemon(true);
        return t;
    });

    public FleetService(FleetStore store, SecretResolver secrets, AdminProperties props, ApplicationEventPublisher events) {
        this.store = store;
        this.secrets = secrets;
        this.props = props;
        this.events = events;
    }

    /** Published after every status check; alerts listen to it. */
    public record StatusEvent(Status previous, Status current) {}

    @PostConstruct
    void start() {
        long every = Math.max(5, props.status().interval().toSeconds());
        ticker.scheduleWithFixedDelay(() -> {
            try {
                refreshAll();
            } catch (RuntimeException e) {
                log.warn("status refresh failed: {}", e.getMessage());
            }
        }, 1, every, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
        pool.shutdownNow();
    }

    public FleetStore store() {
        return store;
    }

    public AgentClient client(String name) {
        Host h = store.require(name);
        CachedClient c = clients.get(name);
        if (c == null || !c.host().equals(h)) {
            c = new CachedClient(h, new AgentClient(h, () -> token(h)));
            clients.put(name, c);
            tokens.remove(name);
        }
        return c.client();
    }

    /** Throws 403 unless the host may be changed. */
    public AgentClient writable(String name) {
        AgentClient c = client(name);
        if (c.host().readOnly()) {
            throw new AgentException(403, name + " is read-only in the control centre; untick read-only in its settings to change it");
        }
        return c;
    }

    private String token(Host h) {
        CachedToken t = tokens.get(h.name());
        if (t != null && t.ref().equals(h.tokenRef()) && Instant.now().isBefore(t.expires())) {
            return t.value();
        }
        String v = secrets.resolve(h.tokenRef(), "host " + h.name());
        tokens.put(h.name(), new CachedToken(h.tokenRef(), v, Instant.now().plusSeconds(60)));
        return v;
    }

    public void forget(String name) {
        clients.remove(name);
        tokens.remove(name);
        status.remove(name);
    }

    // ------------------------------------------------------------------ status

    public List<Status> statuses() {
        List<Status> out = new ArrayList<>();
        for (Host h : store.snapshot().fleet().hosts()) {
            out.add(status.getOrDefault(h.name(), Status.unknown(h.name())));
        }
        return out;
    }

    public Status status(String name) {
        store.require(name);
        return status.getOrDefault(name, Status.unknown(name));
    }

    public void refreshAll() {
        List<Host> hosts = store.snapshot().fleet().hosts();
        status.keySet().retainAll(hosts.stream().map(Host::name).toList());
        fanOut(hosts.stream().map(Host::name).toList(), this::refresh);
    }

    public Status refresh(String name) {
        Duration timeout = props.status().timeout();
        long t0 = System.nanoTime();
        Status s;
        try {
            AgentClient c = client(name);
            JsonNode health = c.health(timeout);
            long latency = (System.nanoTime() - t0) / 1_000_000;
            List<String> features = new ArrayList<>();
            health.path("features").forEach(f -> features.add(f.asText()));
            try {
                JsonNode info = c.info(timeout);
                s = new Status(name, "online", info.path("version").asText(health.path("version").asText(null)),
                        features, info.path("uptime_s").asLong(), latency, Instant.now(), null,
                        info.path("default_cwd").asText(null), info.path("base").asText(null), info.path("name").asText(null));
            } catch (AgentException e) {
                s = new Status(name, e.getMessage().contains("rejected the token") ? "unauthorized" : "error",
                        health.path("version").asText(null), features, null, latency, Instant.now(), e.getMessage(), null, null, null);
            } catch (IllegalStateException e) {  // token could not be resolved
                s = new Status(name, "unauthorized", health.path("version").asText(null), features, null, latency,
                        Instant.now(), e.getMessage(), null, null, null);
            }
        } catch (AgentException e) {
            s = new Status(name, "offline", null, List.of(), null, null, Instant.now(), e.getMessage(), null, null, null);
        } catch (RuntimeException e) {
            s = new Status(name, "error", null, List.of(), null, null, Instant.now(), e.getMessage(), null, null, null);
        }
        if (store.find(name).isEmpty()) {
            return s;  // removed while being checked
        }
        Status prev = status.put(name, s);
        events.publishEvent(new StatusEvent(prev, s));
        if (prev != null && !prev.state().equals(s.state()) && !"unknown".equals(prev.state())) {
            Audit.log(name, "STATE", prev.state() + " -> " + s.state() + (s.message() == null ? "" : " " + s.message()));
        }
        return s;
    }

    /** OS, uptime, load, memory, disk and more, from one command (works with every agent version). */
    public Map<String, Object> sysInfo(String name) {
        JsonNode r = client(name).run(SYSINFO_SCRIPT, "/", 30);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : r.path("output").asText("").split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) {
                out.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ fan-out

    public <T> Map<String, Object> fanOut(List<String> names, Function<String, T> fn) {
        Map<String, Future<T>> futures = new LinkedHashMap<>();
        String actor = Audit.actor();
        for (String n : names) {
            futures.put(n, pool.submit(() -> {
                Audit.actor(actor);
                try {
                    return fn.apply(n);
                } finally {
                    Audit.clear();
                }
            }));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        futures.forEach((n, f) -> {
            try {
                out.put(n, f.get());
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                out.put(n, Map.of("error", c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.put(n, Map.of("error", "interrupted"));
            }
        });
        return out;
    }

    /** Hosts matching a selector: explicit names, or every host in a group / with a tag. */
    public List<String> select(List<String> names, String group, String tag) {
        List<String> out = new ArrayList<>();
        for (Host h : store.snapshot().fleet().hosts()) {
            boolean byName = names != null && names.contains(h.name());
            boolean byGroup = group != null && !group.isBlank() && group.equals(h.group());
            boolean byTag = tag != null && !tag.isBlank() && h.tags().contains(tag);
            if (byName || byGroup || byTag) {
                out.add(h.name());
            }
        }
        if (names != null) {
            for (String n : names) {
                store.require(n);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ upgrade

    public record Bundle(String version, String serverPy, String uiHtml) {}

    public static Bundle bundle() {
        try {
            return new Bundle(resource("VERSION").trim(), resource("server.py"), resource("ui.html"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = FleetService.class.getResourceAsStream("/client-agent/" + name)) {
            if (in == null) {
                throw new IOException("missing " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Push the bundled agent over MCP update_server, then wait for it to come back. */
    public Map<String, Object> upgrade(String name) {
        Bundle b = bundle();
        if (b == null) {
            throw new AgentException(500, "This build has no bundled client agent");
        }
        AgentClient c = writable(name);
        Status before = refresh(name);
        Audit.log(name, "UPGRADE", before.version() + " -> " + b.version());
        JsonNode res = c.mcpTool("update_server", Map.of("code", b.serverPy(), "ui_html", b.uiHtml()), Duration.ofSeconds(60));
        if (res.hasNonNull("error")) {
            throw new AgentException(502, name + " refused the update: " + res.get("error").asText());
        }
        Instant deadline = Instant.now().plusSeconds(60);
        Status after = before;
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            after = refresh(name);
            if ("online".equals(after.state()) && b.version().equals(after.version())) {
                break;
            }
        }
        boolean ok = "online".equals(after.state()) && b.version().equals(after.version());
        Audit.log(name, "UPGRADE", (ok ? "OK" : "NOT CONFIRMED") + " now=" + after.version());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("from", before.version());
        out.put("to", b.version());
        out.put("now", after.version());
        out.put("state", after.state());
        if (!ok) {
            out.put("message", "The agent did not report " + b.version() + " within 60s. If the new code crashed, run.sh rolled it back.");
        }
        return out;
    }
}
