package com.ailabs.orchestrator.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.store.Audit;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.annotation.PreDestroy;

/** Starts runs on virtual threads, keeps recent history, and brokers human approvals. */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OrchestratorProperties props;
    private final Path dir;
    private final Map<String, Run> runs = new LinkedHashMap<>();   // guarded by this; oldest first
    private final Map<String, CompletableFuture<Boolean>> approvals = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public RunService(OrchestratorProperties props) {
        this.props = props;
        this.dir = props.homeDir().resolve("runs");
        load();
    }

    /** Starts {@code work} in the background; its return value becomes the run's result. */
    public Run start(String mode, String title, String input, String agent, String chainId, Function<RunContext, String> work) {
        Run run = new Run();
        run.id = Instant.now().toString().substring(0, 10).replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8);
        run.mode = mode;
        run.title = title == null || title.isBlank() ? mode : (title.length() > 140 ? title.substring(0, 140) + "..." : title);
        run.input = input;
        run.agent = agent;
        run.chainId = chainId;
        run.createdAt = Instant.now();
        synchronized (this) {
            runs.put(run.id, run);
            prune();
        }
        Audit.log("ui", "RUN_START", run.id + " " + mode + (agent == null ? "" : " agent=" + agent) + " " + run.title);
        RunContext ctx = new RunContext(run, this);
        pool.submit(() -> execute(run, ctx, work));
        return run;
    }

    private void execute(Run run, RunContext ctx, Function<RunContext, String> work) {
        run.status = "running";
        event(run, "start", null, run.title, run.input, null);
        save(run);
        try {
            String result = work.apply(ctx);
            run.result = result;
            run.status = run.cancelRequested ? "cancelled" : "succeeded";
            event(run, "done", null, "Finished", null, null);
        } catch (RunContext.Cancelled e) {
            run.status = "cancelled";
            event(run, "error", null, "Cancelled", e.getMessage(), null);
        } catch (RuntimeException e) {
            run.status = "failed";
            run.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            event(run, "error", null, "Failed", run.error, null);
            log.warn("run {} failed", run.id, e);
        } finally {
            run.finishedAt = Instant.now();
            synchronized (run) {
                run.pendingApprovals.clear();
            }
            Audit.log("run", "RUN_END", run.id + " " + run.status);
            save(run);
        }
    }

    void event(Run run, String type, String agent, String title, String detail, String approval) {
        synchronized (run) {
            run.events.add(new RunEvent(run.events.size() + 1, Instant.now(), type, agent, title, detail, approval));
        }
    }

    boolean awaitApproval(Run run, String agent, String title, String detail) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        approvals.put(run.id + "/" + id, f);
        RunEvent ev;
        synchronized (run) {
            ev = new RunEvent(run.events.size() + 1, Instant.now(), "approval", agent, title, detail, id);
            run.events.add(ev);
            run.pendingApprovals.put(id, ev);
        }
        run.status = "waiting";
        long deadline = System.currentTimeMillis() + props.limits().approvalTimeout().toMillis();
        boolean ok = false;
        String how = "timed out";
        try {
            while (true) {
                if (run.cancelRequested) throw new RunContext.Cancelled();
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    ok = f.get(Math.min(left, 1000), TimeUnit.MILLISECONDS);
                    how = ok ? "approved" : "denied";
                    break;
                } catch (TimeoutException e) {
                    // poll again (cancellation)
                } catch (Exception e) {
                    break;
                }
            }
        } finally {
            approvals.remove(run.id + "/" + id);
            synchronized (run) {
                run.pendingApprovals.remove(id);
            }
            if (!run.finished()) run.status = "running";
        }
        event(run, "approval-result", agent, how, title, id);
        Audit.log("ui", "APPROVAL", run.id + " " + how + " " + (agent == null ? "" : agent + " ") + title);
        return ok;
    }

    public void resolveApproval(String runId, String approvalId, boolean approve) {
        CompletableFuture<Boolean> f = approvals.get(runId + "/" + approvalId);
        if (f == null) throw new NoSuchElementException("no pending approval " + approvalId + " on run " + runId);
        f.complete(approve);
    }

    public void cancel(String id) {
        Run r = get(id);
        if (r.finished()) return;
        r.cancelRequested = true;
        Audit.log("ui", "RUN_CANCEL", id);
    }

    public synchronized Run get(String id) {
        Run r = runs.get(id);
        if (r == null) throw new NoSuchElementException("no run " + id);
        return r;
    }

    /** Newest first. */
    public synchronized List<Run> list(int limit) {
        List<Run> all = new ArrayList<>(runs.values());
        java.util.Collections.reverse(all);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    // ------------------------------------------------------------------ persistence

    private void save(Run run) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(run.id + ".json.tmp");
            synchronized (run) {
                JSON.writeValue(tmp.toFile(), run);
            }
            Files.move(tmp, dir.resolve(run.id + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("cannot save run {}: {}", run.id, e.getMessage());
        }
    }

    private void load() {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> list = files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName)).toList();
            int skip = Math.max(0, list.size() - props.limits().maxRuns());
            for (Path p : list.subList(skip, list.size())) {
                try {
                    Run r = JSON.readValue(p.toFile(), Run.class);
                    if (!r.finished()) {
                        r.status = "interrupted";
                        r.error = "the orchestrator restarted while this run was in progress";
                    }
                    runs.put(r.id, r);
                } catch (IOException e) {
                    log.warn("skipping {}: {}", p, e.getMessage());
                }
            }
            for (Path p : list.subList(0, skip)) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("cannot read {}: {}", dir, e.getMessage());
        }
    }

    private void prune() {
        int max = props.limits().maxRuns();
        var it = runs.entrySet().iterator();
        while (runs.size() > max && it.hasNext()) {
            Run r = it.next().getValue();
            if (!r.finished()) continue;
            it.remove();
            try {
                Files.deleteIfExists(dir.resolve(r.id + ".json"));
            } catch (IOException ignored) {
                // next time
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (this) {
            runs.values().forEach(r -> r.cancelRequested = true);
        }
        pool.shutdownNow();
    }
}
