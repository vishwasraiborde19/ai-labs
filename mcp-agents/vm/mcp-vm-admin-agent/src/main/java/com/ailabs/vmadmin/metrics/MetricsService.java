package com.ailabs.vmadmin.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.alerts.AlertService;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.Host;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Samples load, memory, disk and failed units of every online host (default every 5 minutes, kept 7
 * days in {@code metrics/<host>.jsonl}) for the Overview charts, and hands each sample to the alerts.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Sample(long t, Double load1, Integer cpus, Double memPct, Double diskPct, Double swapPct,
                         Integer failedUnits, Boolean rebootRequired) {}

    private final FleetService fleet;
    private final AlertService alerts;
    private final AdminProperties.Metrics cfg;
    private final Path dir;
    private final Map<String, Deque<Sample>> data = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public MetricsService(FleetService fleet, AlertService alerts, AdminProperties props) {
        this(fleet, alerts, props, AdminProperties.home().resolve("metrics"));
    }

    MetricsService(FleetService fleet, AlertService alerts, AdminProperties props, Path dir) {
        this.fleet = fleet;
        this.alerts = alerts;
        this.cfg = props.metrics();
        this.dir = dir;
    }

    @PostConstruct
    void start() {
        load();
        long every = Math.max(30, cfg.interval().toSeconds());
        ticker.scheduleWithFixedDelay(this::sampleAll, 20, every, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    void sampleAll() {
        try {
            List<String> online = fleet.statuses().stream().filter(s -> "online".equals(s.state())).map(FleetService.Status::host).toList();
            fleet.fanOut(online, h -> record(h, fleet.sysInfo(h)));
        } catch (RuntimeException e) {
            log.warn("metrics sampling failed: {}", e.getMessage());
        }
    }

    /** Turn a sysinfo result into a sample, keep it, and check it against the alert thresholds. */
    public Sample record(String host, Map<String, Object> s) {
        Sample sample = parse(s, System.currentTimeMillis());
        Deque<Sample> q = data.computeIfAbsent(host, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(sample);
            long cutoff = sample.t() - cfg.retention().toMillis();
            while (!q.isEmpty() && q.peekFirst().t() < cutoff) {
                q.removeFirst();
            }
        }
        append(host, sample);
        alerts.onSample(host, sample);
        return sample;
    }

    public List<Sample> history(String host, Duration window) {
        Deque<Sample> q = data.get(host);
        if (q == null) {
            return List.of();
        }
        long from = System.currentTimeMillis() - window.toMillis();
        synchronized (q) {
            return q.stream().filter(x -> x.t() >= from).toList();
        }
    }

    static Sample parse(Map<String, Object> s, long now) {
        Double memT = num(s.get("mem_total_kb")), memA = num(s.get("mem_avail_kb"));
        Double swT = num(s.get("swap_total_kb")), swF = num(s.get("swap_free_kb"));
        Double diskPct = null;
        String[] d = String.valueOf(s.getOrDefault("disk_root", "")).trim().split("\\s+");
        if (d.length >= 2) {
            Double t = num(d[0]), u = num(d[1]);
            diskPct = t != null && u != null && t > 0 ? round(u / t * 100) : null;
        }
        Double load1 = num(String.valueOf(s.getOrDefault("load", "")).trim().split("\\s+")[0]);
        Double cpus = num(s.get("cpus"));
        Double failed = num(s.get("failed_units"));
        return new Sample(now, load1, cpus == null ? null : cpus.intValue(),
                memT != null && memA != null && memT > 0 ? round((memT - memA) / memT * 100) : null,
                diskPct,
                swT != null && swF != null && swT > 0 ? round((swT - swF) / swT * 100) : null,
                failed == null ? null : failed.intValue(),
                s.containsKey("reboot_required") ? "yes".equals(s.get("reboot_required")) : null);
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ------------------------------------------------------------------ persistence

    private Path file(String host) {
        return dir.resolve(host + ".jsonl");
    }

    private void append(String host, Sample s) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(host), JSON.writeValueAsString(s) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("cannot write metrics for {}: {}", host, e.getMessage());
        }
    }

    /** Read every host's samples and compact the files to the retention window. */
    void load() {
        List<String> hosts = fleet.store().snapshot().fleet().hosts().stream().map(Host::name).toList();
        long cutoff = System.currentTimeMillis() - cfg.retention().toMillis();
        for (String h : hosts) {
            Path f = file(h);
            if (!Files.exists(f)) {
                continue;
            }
            try {
                List<String> keep = new ArrayList<>();
                Deque<Sample> q = new ArrayDeque<>();
                for (String line : Files.readAllLines(f)) {
                    try {
                        Sample s = JSON.readValue(line, Sample.class);
                        if (s.t() >= cutoff) {
                            q.addLast(s);
                            keep.add(line);
                        }
                    } catch (IOException ignored) {
                        // skip a damaged line
                    }
                }
                data.put(h, q);
                Files.write(f, keep);
            } catch (IOException e) {
                log.warn("cannot read metrics for {}: {}", h, e.getMessage());
            }
        }
    }
}
