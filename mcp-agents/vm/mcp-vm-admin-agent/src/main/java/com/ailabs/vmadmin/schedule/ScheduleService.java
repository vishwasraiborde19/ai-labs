package com.ailabs.vmadmin.schedule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.Host;
import com.ailabs.vmadmin.guard.CommandGuard;
import com.ailabs.vmadmin.ops.Operations;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Commands that run on a cron across hosts, a group or a tag ({@code schedules.yml}). Read-only and
 * offline hosts are skipped. Risky commands need {@code confirmed: true}, set when the user confirms
 * on save. The last 20 runs of each schedule are kept in memory; every run is audited.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final int KEEP_RUNS = 20;

    public record Schedule(String id, String name, String cron, List<String> hosts, String group, String tag,
                           String command, Integer timeoutSeconds, boolean enabled, boolean confirmed) {
        public Schedule {
            hosts = hosts != null ? List.copyOf(hosts) : List.of();
        }
    }

    public record Run(Instant at, long durationMs, int ok, int failed, int skipped, String error, Map<String, Object> results) {}

    record File(List<Schedule> schedules) {
        File {
            schedules = schedules != null ? List.copyOf(schedules) : List.of();
        }
    }

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_DEFAULT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private final Operations ops;
    private final FleetService fleet;
    private final CommandGuard guard;
    private final Map<String, Deque<Run>> runs = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCheck = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "schedules");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleService(Operations ops, CommandGuard guard) {
        this(AdminProperties.home().resolve("schedules.yml"), ops, guard);
    }

    ScheduleService(Path file, Operations ops, CommandGuard guard) {
        this.file = file;
        this.ops = ops;
        this.fleet = ops.fleet();
        this.guard = guard;
    }

    @PostConstruct
    void start() {
        Instant now = Instant.now();
        list().forEach(s -> lastCheck.put(s.id(), now));
        ticker.scheduleWithFixedDelay(this::tick, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    // ------------------------------------------------------------------ store

    public synchronized List<Schedule> list() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String text = Files.readString(file);
            return text.isBlank() ? List.of() : YAML.readValue(text, File.class).schedules();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public Schedule get(String id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No schedule " + id));
    }

    /** Create (id null) or replace. A risky command must come with confirm=true. */
    public synchronized Schedule save(Schedule s, boolean confirm) {
        if (s.name() == null || s.name().isBlank() || s.name().length() > 80) {
            throw new IllegalArgumentException("name is required (max 80 characters)");
        }
        if (s.command() == null || s.command().isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        CronExpression cron = parseCron(s.cron());
        if (s.hosts().isEmpty() && blank(s.group()) && blank(s.tag())) {
            throw new IllegalArgumentException("pick hosts, a group or a tag");
        }
        boolean risky = guard.check(s.command()).isPresent();
        if (risky && !confirm && !(s.id() != null && sameCommandConfirmed(s))) {
            throw new Operations.NeedsConfirmation(guard.check(s.command()).get());
        }
        String id = s.id() != null ? s.id() : HexFormat.of().formatHex(new SecureRandom().generateSeed(4));
        Schedule saved = new Schedule(id, s.name().trim(), s.cron().trim(), s.hosts(), blank(s.group()) ? null : s.group(),
                blank(s.tag()) ? null : s.tag(), s.command(), s.timeoutSeconds(), s.enabled(), risky);
        List<Schedule> all = new ArrayList<>(list());
        all.removeIf(x -> x.id().equals(id));
        all.add(saved);
        write(all);
        lastCheck.put(id, Instant.now());
        Audit.log(target(saved), "SCHEDULE_SAVE", saved.name() + " cron=" + saved.cron() + " enabled=" + saved.enabled()
                + " cmd=" + saved.command() + " next=" + next(cron));
        return saved;
    }

    private boolean sameCommandConfirmed(Schedule s) {
        return list().stream().anyMatch(x -> x.id().equals(s.id()) && x.confirmed() && x.command().equals(s.command()));
    }

    public synchronized void delete(String id) {
        Schedule s = get(id);
        List<Schedule> all = new ArrayList<>(list());
        all.removeIf(x -> x.id().equals(id));
        write(all);
        runs.remove(id);
        lastCheck.remove(id);
        Audit.log(target(s), "SCHEDULE_DELETE", s.name());
    }

    private void write(List<Schedule> all) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, "# Managed by mcp-vm-admin-agent (Schedules view).\n" + YAML.writeValueAsString(new File(all)),
                    StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ cron

    /** Accepts 5-field cron ({@code min hour dom mon dow}), 6-field (with seconds) and @hourly, @daily, ... */
    public static CronExpression parseCron(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("cron is required, e.g. '0 3 * * *' for 03:00 daily");
        }
        String e = expr.trim();
        if (!e.startsWith("@") && e.split("\\s+").length == 5) {
            e = "0 " + e;
        }
        try {
            return CronExpression.parse(e);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cron '" + expr + "': " + ex.getMessage());
        }
    }

    public static Instant next(CronExpression cron) {
        LocalDateTime n = cron.next(LocalDateTime.now());
        return n == null ? null : n.atZone(ZoneId.systemDefault()).toInstant();
    }

    // ------------------------------------------------------------------ running

    void tick() {
        Instant now = Instant.now();
        for (Schedule s : list()) {
            if (!s.enabled()) {
                lastCheck.put(s.id(), now);
                continue;
            }
            Instant from = lastCheck.getOrDefault(s.id(), now);
            lastCheck.put(s.id(), now);
            try {
                LocalDateTime due = parseCron(s.cron()).next(LocalDateTime.ofInstant(from, ZoneId.systemDefault()));
                if (due != null && !due.atZone(ZoneId.systemDefault()).toInstant().isAfter(now)) {
                    Thread.ofVirtual().start(() -> runNow(s.id(), "schedule:" + s.name()));
                }
            } catch (RuntimeException e) {
                log.warn("schedule {} skipped: {}", s.name(), e.getMessage());
            }
        }
    }

    /** Run a schedule now (also used by the timer). Overlapping runs of one schedule are skipped. */
    public Run runNow(String id, String actor) {
        Schedule s = get(id);
        if (running.putIfAbsent(id, true) != null) {
            throw new IllegalStateException(s.name() + " is already running");
        }
        String prevActor = Audit.actor();
        Audit.actor(actor);
        long t0 = System.currentTimeMillis();
        Run run;
        try {
            List<String> targets = new ArrayList<>();
            Map<String, Object> results = new LinkedHashMap<>();
            for (String h : fleet.select(s.hosts().stream().filter(n -> fleet.store().find(n).isPresent()).toList(), s.group(), s.tag())) {
                Host host = fleet.store().require(h);
                FleetService.Status st = fleet.status(h);
                if (host.readOnly()) {
                    results.put(h, Map.of("skipped", "read-only"));
                } else if ("offline".equals(st.state())) {
                    results.put(h, Map.of("skipped", "offline"));
                } else {
                    targets.add(h);
                }
            }
            int skipped = results.size();
            String error = null;
            if (!targets.isEmpty()) {
                try {
                    results.putAll(ops.broadcast(targets, s.command(), null, s.timeoutSeconds(), s.confirmed()));
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
            } else if (skipped == 0) {
                error = "no hosts match";
            }
            int ok = 0, failed = 0;
            for (Map.Entry<String, Object> e : results.entrySet()) {
                if (e.getValue() instanceof com.fasterxml.jackson.databind.JsonNode j) {
                    if (j.path("exit_code").isInt() && j.path("exit_code").asInt() == 0) {
                        ok++;
                    } else {
                        failed++;
                    }
                    String outText = j.path("output").asText("");
                    e.setValue(Map.of("exit_code", j.path("exit_code").isNull() ? "timeout" : j.path("exit_code").asText(),
                            "output", outText.length() > 4000 ? "..." + outText.substring(outText.length() - 4000) : outText));
                } else if (e.getValue() instanceof Map<?, ?> m && m.containsKey("error")) {
                    failed++;
                }
            }
            run = new Run(Instant.ofEpochMilli(t0), System.currentTimeMillis() - t0, ok, failed, skipped, error, results);
            Audit.log(target(s), "SCHEDULE_RUN", s.name() + " ok=" + ok + " failed=" + failed + " skipped=" + skipped
                    + (error != null ? " error=" + error : ""));
        } finally {
            running.remove(id);
            Audit.actor(prevActor);
        }
        Deque<Run> q = runs.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addFirst(run);
            while (q.size() > KEEP_RUNS) {
                q.removeLast();
            }
        }
        return run;
    }

    public List<Run> runs(String id) {
        Deque<Run> q = runs.get(id);
        if (q == null) {
            return List.of();
        }
        synchronized (q) {
            return List.copyOf(q);
        }
    }

    public Map<String, Object> view(Schedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schedule", s);
        Instant next = null;
        try {
            next = s.enabled() ? next(parseCron(s.cron())) : null;
        } catch (IllegalArgumentException ignored) {
            // shown as no next run
        }
        m.put("next", next);
        m.put("running", running.containsKey(s.id()));
        List<Run> r = runs(s.id());
        m.put("last", r.isEmpty() ? null : r.get(0));
        return m;
    }

    private static String target(Schedule s) {
        List<String> t = new ArrayList<>(s.hosts());
        if (!blank(s.group())) {
            t.add("group:" + s.group());
        }
        if (!blank(s.tag())) {
            t.add("tag:" + s.tag());
        }
        return String.join(",", t);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
