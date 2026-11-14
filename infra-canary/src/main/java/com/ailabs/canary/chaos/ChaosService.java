package com.ailabs.canary.chaos;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Faults this pod can inject into itself, so the platform's reaction can be watched: Kubernetes probes and
 * restarts, Service endpoints, Prometheus/Grafana alerts, Longhorn volume space.
 *
 * Every fault is real (memory is really allocated, CPU really burned, files really written) except the mock
 * gauges, which only publish numbers. Faults with a duration revert themselves; 0 means until cleared.
 * State lives in this JVM only: a container restart clears everything.
 */
@Service
public class ChaosService {
    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);
    static final List<String> FAULTS = List.of("health", "readiness", "liveness", "memory", "cpu", "latency", "errors", "disk");
    private static final Pattern MOCK_NAME = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final int MAX_MOCKS = 20;
    private static final long MB = 1024 * 1024;

    private final ApplicationEventPublisher events;
    private final ApplicationAvailability availability;
    private final MeterRegistry meters;
    private final Path fillDir;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "chaos-timer"));
    private final Map<String, ScheduledFuture<?>> reverts = new ConcurrentHashMap<>();
    private final Map<String, Instant> expires = new ConcurrentHashMap<>();

    // health indicator "chaos"
    private volatile Status healthStatus = Status.UP;
    private volatile String healthReason = "";
    // memory ballast (heap byte[] or direct ByteBuffer, 1 MiB each)
    private final List<Object> ballast = new ArrayList<>();
    private volatile long heapMb;
    private volatile long directMb;
    // cpu burners: a thread stops when its generation is no longer current
    private final AtomicInteger cpuGeneration = new AtomicInteger();
    private volatile int cpuThreads;
    // request faults, applied by ChaosFilter
    private volatile long latencyMs;
    private volatile long jitterMs;
    private volatile boolean latencyOnActuator;
    private volatile int errorPercent;
    private volatile int errorStatus = 500;
    // disk fill
    private volatile long diskMb;
    // mock gauges
    private final Map<String, Double> mocks = new ConcurrentHashMap<>();
    private final Map<String, Gauge> mockGauges = new ConcurrentHashMap<>();

    public ChaosService(ApplicationEventPublisher events, ApplicationAvailability availability, MeterRegistry meters,
                        @Value("${logging.file.name:}") String logFile) {
        this.events = events;
        this.availability = availability;
        this.meters = meters;
        this.fillDir = logFile.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"))
                                         : Path.of(logFile).toAbsolutePath().getParent();
        for (String f : FAULTS) {
            Gauge.builder("canary.chaos.active", this, s -> s.isActive(f) ? 1 : 0)
                 .tag("fault", f).description("1 while this chaos fault is on").register(meters);
        }
    }

    // ------------------------------------------------------------------ health / probes

    public synchronized void setHealth(Status status, String reason, long seconds) {
        healthStatus = status;
        healthReason = reason == null ? "" : reason;
        boolean on = !Status.UP.equals(status);
        audit("health", on ? "status " + status.getCode() + " (" + healthReason + ")" : "UP", on ? seconds : 0,
              () -> setHealth(Status.UP, "", 0));
    }

    public void setReadiness(boolean refuse, long seconds) {
        AvailabilityChangeEvent.publish(events, this, refuse ? ReadinessState.REFUSING_TRAFFIC : ReadinessState.ACCEPTING_TRAFFIC);
        audit("readiness", refuse ? "REFUSING_TRAFFIC" : "ACCEPTING_TRAFFIC", refuse ? seconds : 0, () -> setReadiness(false, 0));
    }

    public void setLiveness(boolean broken, long seconds) {
        AvailabilityChangeEvent.publish(events, this, broken ? LivenessState.BROKEN : LivenessState.CORRECT);
        audit("liveness", broken ? "BROKEN" : "CORRECT", broken ? seconds : 0, () -> setLiveness(false, 0));
    }

    public Status healthStatus() { return healthStatus; }
    public String healthReason() { return healthReason; }

    // ------------------------------------------------------------------ memory

    /** Adds mb MiB of ballast. Heap is capped at 90% of -Xmx unless allowOom; direct memory is not capped here. */
    public synchronized Map<String, Object> allocate(long mb, boolean direct, boolean allowOom, long seconds) {
        if (mb < 1 || mb > 16_384) throw new IllegalArgumentException("mb must be 1..16384");
        long added = 0;
        if (!direct && !allowOom) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long room = (long) (heap.getMax() * 0.9 - heap.getUsed()) / MB;
            if (mb > room) throw new IllegalArgumentException("only " + Math.max(room, 0) + " MiB heap left below 90% of max; "
                    + "lower mb or set allowOom to push into OutOfMemoryError");
        }
        try {
            for (; added < mb; added++) {
                if (direct) {
                    ByteBuffer b = ByteBuffer.allocateDirect((int) MB);
                    for (int i = 0; i < MB; i += 4096) b.put(i, (byte) 1);     // touch every page so it is resident
                    ballast.add(b);
                    directMb++;
                } else {
                    byte[] b = new byte[(int) MB];
                    Arrays.fill(b, (byte) 1);
                    ballast.add(b);
                    heapMb++;
                }
            }
        } catch (OutOfMemoryError e) {
            log.error("chaos memory: {} after {} MiB of {} requested (ballast kept: heap {} MiB, direct {} MiB)",
                      e.getMessage(), added, mb, heapMb, directMb);
            audit("memory", "heap " + heapMb + " MiB, direct " + directMb + " MiB (hit OutOfMemoryError)", seconds, this::release);
            return Map.of("allocatedMb", added, "requestedMb", mb, "outOfMemory", e.getMessage());
        }
        audit("memory", "heap " + heapMb + " MiB, direct " + directMb + " MiB", seconds, this::release);
        return Map.of("allocatedMb", added, "heapMb", heapMb, "directMb", directMb);
    }

    public synchronized void release() {
        long h = heapMb, d = directMb;
        ballast.clear();
        heapMb = 0;
        directMb = 0;
        System.gc();
        audit("memory", h + d > 0 ? "released heap " + h + " MiB, direct " + d + " MiB" : "nothing allocated", 0, null);
    }

    // ------------------------------------------------------------------ cpu

    public synchronized void burnCpu(int threads, long seconds) {
        int max = Runtime.getRuntime().availableProcessors() * 4;
        if (threads < 1 || threads > max) throw new IllegalArgumentException("threads must be 1.." + max);
        int gen = cpuGeneration.incrementAndGet();
        cpuThreads = threads;
        for (int i = 0; i < threads; i++) {
            Thread t = daemon(() -> {
                double x = 1;
                while (cpuGeneration.get() == gen) {
                    for (int k = 0; k < 100_000; k++) x = Math.sqrt(x + k) * 1.0000001;
                }
                if (x == 42) log.trace("unreachable {}", x);       // keep the loop from being optimised away
            }, "chaos-cpu-" + gen + "-" + i);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        audit("cpu", threads + " busy threads", seconds, this::stopCpu);
    }

    public synchronized void stopCpu() {
        cpuGeneration.incrementAndGet();
        int was = cpuThreads;
        cpuThreads = 0;
        audit("cpu", was > 0 ? "stopped " + was + " threads" : "nothing running", 0, null);
    }

    // ------------------------------------------------------------------ latency / errors

    public void setLatency(long ms, long jitter, boolean actuator, long seconds) {
        if (ms < 0 || ms > 120_000 || jitter < 0 || jitter > 60_000) throw new IllegalArgumentException("ms 0..120000, jitter 0..60000");
        latencyMs = ms;
        jitterMs = jitter;
        latencyOnActuator = actuator;
        boolean on = ms + jitter > 0;
        audit("latency", on ? ms + " ms +0.." + jitter + " ms" + (actuator ? " (actuator too)" : "") : "off",
              on ? seconds : 0, () -> setLatency(0, 0, false, 0));
    }

    public void setErrors(int percent, int status, long seconds) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent must be 0..100");
        if (status < 400 || status > 599) throw new IllegalArgumentException("status must be 400..599");
        errorPercent = percent;
        errorStatus = status;
        audit("errors", percent > 0 ? percent + "% of requests -> " + status : "off", percent > 0 ? seconds : 0,
              () -> setErrors(0, 500, 0));
    }

    /** Delay to add to a request, 0 for none. */
    long delayFor(boolean actuator) {
        if (actuator && !latencyOnActuator) return 0;
        long j = jitterMs;
        return latencyMs + (j > 0 ? ThreadLocalRandom.current().nextLong(j + 1) : 0);
    }

    /** HTTP status to fail this request with, or 0. */
    int errorFor() {
        int p = errorPercent;
        return p > 0 && ThreadLocalRandom.current().nextInt(100) < p ? errorStatus : 0;
    }

    // ------------------------------------------------------------------ disk

    /** Writes mb MiB to .chaos-fill next to the log file (the Longhorn volume in the cluster). */
    public synchronized Map<String, Object> fillDisk(long mb, long seconds) throws IOException {
        Files.createDirectories(fillDir);                      // logback creates it only on the first file write
        long free = Files.getFileStore(fillDir).getUsableSpace() / MB;
        if (mb < 1 || mb > free) throw new IllegalArgumentException("mb must be 1.." + free + " (free space on " + fillDir + ")");
        Path f = fillDir.resolve(".chaos-fill");
        byte[] chunk = new byte[(int) MB];
        ThreadLocalRandom.current().nextBytes(chunk);          // incompressible, really allocated
        long written = 0;
        try (OutputStream out = Files.newOutputStream(f, java.nio.file.StandardOpenOption.CREATE,
                                                      java.nio.file.StandardOpenOption.APPEND)) {
            for (; written < mb; written++) out.write(chunk);
        } catch (IOException e) {
            log.error("chaos disk: write failed after {} MiB: {}", written, e.toString());
        }
        diskMb += written;
        audit("disk", diskMb + " MiB in " + f, seconds, () -> { try { releaseDisk(); } catch (IOException e) { log.warn("{}", e.toString()); } });
        return Map.of("writtenMb", written, "totalMb", diskMb, "file", f.toString());
    }

    public synchronized void releaseDisk() throws IOException {
        boolean had = Files.deleteIfExists(fillDir.resolve(".chaos-fill"));
        long was = diskMb;
        diskMb = 0;
        audit("disk", had ? "deleted " + was + " MiB" : "nothing to delete", 0, null);
    }

    // ------------------------------------------------------------------ mock gauges

    /** Publishes canary_mock_value{name=...} = value. Only a number: nothing in the JVM changes. */
    public void setMock(String name, double value) {
        if (!MOCK_NAME.matcher(name).matches()) throw new IllegalArgumentException("name: lower case, digits, _ (max 40)");
        if (!mocks.containsKey(name) && mocks.size() >= MAX_MOCKS) throw new IllegalArgumentException("at most " + MAX_MOCKS + " mock values");
        mocks.put(name, value);
        mockGauges.computeIfAbsent(name, n -> Gauge.builder("canary.mock.value", mocks, m -> m.getOrDefault(n, Double.NaN))
                .tag("name", n).description("value set in the chaos console").register(meters));
        log.warn("CHAOS mock {} = {}", name, value);
    }

    public void clearMock(String name) {
        mocks.remove(name);
        Gauge g = mockGauges.remove(name);
        if (g != null) meters.remove(g);
        log.warn("CHAOS mock {} cleared", name);
    }

    public void clearMocks() {
        new ArrayList<>(mocks.keySet()).forEach(this::clearMock);
    }

    // ------------------------------------------------------------------ all

    public void resetAll() {
        setHealth(Status.UP, "", 0);
        setReadiness(false, 0);
        setLiveness(false, 0);
        release();
        stopCpu();
        setLatency(0, 0, false, 0);
        setErrors(0, 500, 0);
        try { releaseDisk(); } catch (IOException e) { log.warn("chaos reset: disk {}", e.toString()); }
        clearMocks();
        log.warn("CHAOS reset: every fault cleared");
    }

    /** Stops the JVM: exit runs shutdown hooks (like SIGTERM), halt does not (like a crash). */
    public void crash(boolean halt, int code) {
        log.error("CHAOS crash requested: {} with code {}", halt ? "halt" : "exit", code);
        timer.schedule(() -> {
            if (halt) Runtime.getRuntime().halt(code); else System.exit(code);
        }, 500, TimeUnit.MILLISECONDS);
    }

    public boolean isActive(String fault) {
        return switch (fault) {
            case "health" -> !Status.UP.equals(healthStatus);
            case "readiness" -> availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC;
            case "liveness" -> availability.getLivenessState() == LivenessState.BROKEN;
            case "memory" -> heapMb + directMb > 0;
            case "cpu" -> cpuThreads > 0;
            case "latency" -> latencyMs + jitterMs > 0;
            case "errors" -> errorPercent > 0;
            case "disk" -> diskMb > 0;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> state() {
        Map<String, Object> faults = new LinkedHashMap<>();
        for (String f : FAULTS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", isActive(f));
            Instant e = expires.get(f);
            if (e != null && isActive(f)) m.put("revertsAt", e.toString());
            faults.put(f, m);
        }
        ((Map<String, Object>) faults.get("health")).putAll(Map.of("status", healthStatus.getCode(), "reason", healthReason));
        ((Map<String, Object>) faults.get("readiness")).put("state", availability.getReadinessState().toString());
        ((Map<String, Object>) faults.get("liveness")).put("state", availability.getLivenessState().toString());
        ((Map<String, Object>) faults.get("memory")).putAll(Map.of("heapMb", heapMb, "directMb", directMb));
        ((Map<String, Object>) faults.get("cpu")).put("threads", cpuThreads);
        ((Map<String, Object>) faults.get("latency")).putAll(Map.of("ms", latencyMs, "jitterMs", jitterMs, "actuator", latencyOnActuator));
        ((Map<String, Object>) faults.get("errors")).putAll(Map.of("percent", errorPercent, "status", errorStatus));
        ((Map<String, Object>) faults.get("disk")).putAll(Map.of("mb", diskMb, "dir", fillDir.toString()));

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("faults", faults);
        s.put("mocks", new TreeMap<>(mocks));
        s.put("resources", resources());
        return s;
    }

    private Map<String, Object> resources() {
        Map<String, Object> r = new LinkedHashMap<>();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        r.put("heapUsedMb", heap.getUsed() / MB);
        r.put("heapMaxMb", heap.getMax() / MB);
        long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(b -> "direct".equals(b.getName())).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        r.put("directUsedMb", direct / MB);
        // container memory (cgroup v2, then v1); what the kubelet compares with limits.memory
        long used = readLong("/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes");
        long limit = readLong("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes");
        if (used > 0) r.put("containerUsedMb", used / MB);
        if (limit > 0 && limit < Long.MAX_VALUE / 2) r.put("containerLimitMb", limit / MB);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            r.put("processCpuPercent", Math.round(Math.max(os.getProcessCpuLoad(), 0) * 1000) / 10.0);
        }
        r.put("cpus", Runtime.getRuntime().availableProcessors());
        try {
            var store = Files.getFileStore(fillDir);
            r.put("volumeDir", fillDir.toString());
            r.put("volumeTotalMb", store.getTotalSpace() / MB);
            r.put("volumeFreeMb", store.getUsableSpace() / MB);
        } catch (IOException e) {
            r.put("volumeError", e.toString());
        }
        return r;
    }

    // ------------------------------------------------------------------ helpers

    /** Logs the change (WARN, so it stands out in the log volume) and (re)schedules the automatic revert. */
    private void audit(String fault, String what, long seconds, Runnable revert) {
        ScheduledFuture<?> old = reverts.remove(fault);
        if (old != null) old.cancel(false);
        expires.remove(fault);
        if (seconds > 0 && revert != null) {
            reverts.put(fault, timer.schedule(() -> {
                log.warn("CHAOS {} reverting after {}s", fault, seconds);
                reverts.remove(fault);
                expires.remove(fault);
                revert.run();
            }, seconds, TimeUnit.SECONDS));
            expires.put(fault, Instant.now().plusSeconds(seconds));
        }
        log.warn("CHAOS {}: {}{}", fault, what, seconds > 0 && revert != null ? " for " + seconds + "s" : "");
    }

    private static long readLong(String... paths) {
        for (String p : paths) {
            try {
                String v = Files.readString(Path.of(p)).trim();
                return "max".equals(v) ? Long.MAX_VALUE : Long.parseLong(v);
            } catch (IOException | NumberFormatException e) {
                // try the next one
            }
        }
        return -1;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
