package com.ailabs.orchestrator.registry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.mcp.HttpMcpClient;
import com.ailabs.orchestrator.mcp.McpClient;
import com.ailabs.orchestrator.mcp.McpServerInfo;
import com.ailabs.orchestrator.mcp.McpTool;
import com.ailabs.orchestrator.mcp.StdioMcpClient;
import com.ailabs.orchestrator.mcp.ToolCallResult;
import com.ailabs.orchestrator.store.Audit;
import com.ailabs.orchestrator.store.Secrets;
import com.ailabs.orchestrator.store.YamlFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PreDestroy;

/**
 * The team: agents from {@code agents.yml} plus drop-in files in {@code agents.d/}, their live MCP connections and
 * tool catalogues, and the inbox of self-registered or discovered agents waiting for approval.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final OrchestratorProperties props;
    private final Secrets secrets;
    private final Path agentsFile;
    private final Path dropinDir;
    private final Path pendingFile;

    private final Map<String, AgentSpec> agents = new LinkedHashMap<>();   // guarded by this
    private final Set<String> dropinOnly = new HashSet<>();                 // guarded by this
    private final Map<String, PendingAgent> pending = new LinkedHashMap<>(); // guarded by this
    private final Map<String, AgentState> states = new ConcurrentHashMap<>();
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AgentRegistry(OrchestratorProperties props, Secrets secrets) {
        this.props = props;
        this.secrets = secrets;
        Path home = props.homeDir();
        this.agentsFile = home.resolve("agents.yml");
        this.dropinDir = home.resolve("agents.d");
        this.pendingFile = home.resolve("pending.yml");
        for (AgentSpec a : YamlFiles.read(agentsFile, new TypeReference<List<AgentSpec>>() {}, ArrayList::new)) {
            a.normalize();
            agents.put(a.id, a);
        }
        for (PendingAgent p : YamlFiles.read(pendingFile, new TypeReference<List<PendingAgent>>() {}, ArrayList::new)) {
            pending.put(p.spec.id, p);
        }
        try {
            Files.createDirectories(dropinDir);
        } catch (IOException e) {
            log.warn("cannot create {}: {}", dropinDir, e.getMessage());
        }
        scanDropins();
    }

    // ------------------------------------------------------------------ queries

    public synchronized List<AgentSpec> list() {
        return agents.values().stream().map(AgentSpec::copy)
                .sorted(Comparator.comparing(AgentSpec::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public synchronized AgentSpec spec(String id) {
        AgentSpec a = agents.get(id);
        if (a == null) throw new NoSuchElementException("no agent " + id);
        return a.copy();
    }

    public synchronized boolean isDropin(String id) {
        return dropinOnly.contains(id);
    }

    public AgentState state(String id) {
        return states.computeIfAbsent(id, k -> new AgentState());
    }

    /** Tools the LLM may use for this agent (disabled-tools removed). */
    public List<McpTool> llmTools(String id) {
        AgentSpec a = spec(id);
        return state(id).tools.stream().filter(t -> !Globs.any(a.disabledTools, t.name())).toList();
    }

    // ------------------------------------------------------------------ edits

    /** Creates or replaces an agent. A non-blank {@code token} is stored and referenced as {@code store:agent.<id>}. */
    public AgentSpec save(AgentSpec in, String token, boolean create) {
        in.normalize();
        in.validate();
        synchronized (this) {
            if (create && agents.containsKey(in.id)) throw new IllegalArgumentException("an agent with id " + in.id + " already exists");
            if (!create && !agents.containsKey(in.id)) throw new NoSuchElementException("no agent " + in.id);
            if (token != null && !token.isBlank()) in.tokenRef = secrets.put("agent." + in.id, token.strip());
            AgentSpec old = agents.get(in.id);
            if (old != null) {
                in.createdAt = old.createdAt;
                if (old.source != null && !"manual".equals(old.source)) in.source = old.source;
            }
            agents.put(in.id, in);
            dropinOnly.remove(in.id);
            persist();
        }
        Audit.log("ui", create ? "AGENT_ADD" : "AGENT_EDIT", in.id + " " + in.transport + " " + target(in));
        dropClient(in.id);
        if (!in.enabled) state(in.id).status = "disabled";
        refreshAsync(in.id);
        return in.copy();
    }

    public void delete(String id) {
        synchronized (this) {
            if (!agents.containsKey(id)) throw new NoSuchElementException("no agent " + id);
            if (dropinOnly.contains(id)) {
                throw new IllegalStateException("agent " + id + " comes from a file in " + dropinDir + "; delete that file instead");
            }
            agents.remove(id);
            persist();
        }
        dropClient(id);
        states.remove(id);
        secrets.remove("agent." + id);
        Audit.log("ui", "AGENT_DELETE", id);
    }

    private void persist() {
        List<AgentSpec> out = agents.values().stream().filter(a -> !dropinOnly.contains(a.id)).toList();
        YamlFiles.write(agentsFile, out, false);
    }

    // ------------------------------------------------------------------ connections

    private McpClient newClient(AgentSpec a) {
        if ("stdio".equals(a.transport)) {
            Map<String, String> env = new LinkedHashMap<>();
            a.env.forEach((k, v) -> env.put(k, v != null && (v.startsWith("env:") || v.startsWith("path:") || v.startsWith("store:"))
                    ? secrets.resolve(v) : v));
            Path wd = a.workdir == null || a.workdir.isBlank() ? null : Path.of(a.workdir);
            return new StdioMcpClient(a.id, a.command, env, wd);
        }
        String ref = a.tokenRef;
        Supplier<String> token = () -> secrets.resolve(ref);
        return new HttpMcpClient(URI.create(a.url), token);
    }

    /** Connected, initialized client for an agent (reconnects when the old one died). */
    private McpClient client(String id) {
        McpClient c = clients.get(id);
        if (c != null && c.alive() && c.info() != null) return c;
        synchronized (locks.computeIfAbsent(id, k -> new Object())) {
            c = clients.get(id);
            if (c != null && c.alive() && c.info() != null) return c;
            if (c != null) c.close();
            AgentSpec a = spec(id);
            if (!a.enabled) throw new IllegalStateException(a.displayName() + " is disabled");
            McpClient fresh = newClient(a);
            try {
                fresh.initialize();
            } catch (RuntimeException e) {
                fresh.close();
                throw e;
            }
            clients.put(id, fresh);
            return fresh;
        }
    }

    private void dropClient(String id) {
        McpClient c = clients.remove(id);
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
    }

    /** Reconnects if needed and reloads the tool catalogue. */
    public AgentState refresh(String id) {
        AgentState st = state(id);
        AgentSpec a = spec(id);
        if (!a.enabled) {
            dropClient(id);
            st.status = "disabled";
            st.error = null;
            return st;
        }
        try {
            McpClient c = client(id);
            List<McpTool> tools = c.listTools();
            String before = st.toolsChanged;
            st.online(c.info(), tools);
            if (st.toolsChanged != null && !st.toolsChanged.equals(before)) {
                Audit.log("registry", "TOOLS_CHANGED", id + ": " + st.toolsChanged);
            }
        } catch (RuntimeException e) {
            dropClient(id);
            st.offline(message(e));
        }
        return st;
    }

    public void refreshAsync(String id) {
        Thread.ofVirtual().start(() -> refresh(id));
    }

    /** Health check of every agent, drop-in rescan and discovery. */
    @Scheduled(initialDelay = 1000, fixedDelayString = "${orchestrator.limits.refresh-ms:60000}")
    public void refreshAll() {
        scanDropins();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (AgentSpec a : list()) pool.submit(() -> refresh(a.id));
            pool.submit(this::discover);
        }
    }

    /** Throws unless the agent is reachable now. */
    public AgentState ensureOnline(String id) {
        AgentState st = state(id);
        if (!"online".equals(st.status) || !clients.containsKey(id)) st = refresh(id);
        if (!"online".equals(st.status)) {
            throw new IllegalStateException(spec(id).displayName() + " is " + st.status
                    + (st.error == null ? "" : " (" + st.error + ")"));
        }
        return st;
    }

    public ToolCallResult callTool(String id, String tool, Map<String, Object> args, String actor) {
        AgentState st = state(id);
        st.calls.incrementAndGet();
        st.lastActive = Instant.now();
        Audit.log(actor, "TOOL", id + "." + tool + " " + args);
        try {
            McpClient c = client(id);
            ToolCallResult r = c.callTool(tool, args, props.limits().toolTimeout());
            if (r.error()) st.failures.incrementAndGet();
            return r;
        } catch (RuntimeException e) {
            st.failures.incrementAndGet();
            dropClient(id);
            refreshAsync(id);
            throw e;
        }
    }

    /** Background call by the orchestrator itself (system discovery, health): no stats, no audit line. */
    public ToolCallResult probeTool(String id, String tool, Map<String, Object> args) {
        try {
            return client(id).callTool(tool, args, Duration.ofSeconds(60));
        } catch (RuntimeException e) {
            dropClient(id);
            throw e;
        }
    }

    public void markBusy(String id, String task) {
        AgentState st = state(id);
        st.busy.incrementAndGet();
        st.tasks.incrementAndGet();
        st.currentTask = task;
        st.lastActive = Instant.now();
    }

    public void markIdle(String id) {
        AgentState st = state(id);
        if (st.busy.decrementAndGet() <= 0) {
            st.busy.set(0);
            st.currentTask = null;
        }
    }

    // ------------------------------------------------------------------ probing and discovery

    public record Probe(McpServerInfo server, List<McpTool> tools) {}

    /** Connects to an agent that is not saved yet and lists its tools. */
    public Probe probe(AgentSpec a, String token) {
        a.normalize();
        a.validate();
        McpClient c;
        if ("http".equals(a.transport) && token != null && !token.isBlank()) {
            String t = token.strip();
            c = new HttpMcpClient(URI.create(a.url), () -> t);
        } else {
            c = newClient(a);
        }
        try {
            McpServerInfo info = c.initialize();
            return new Probe(info, c.listTools());
        } finally {
            c.close();
        }
    }

    /** Re-reads {@code agents.d/*.yml}: each file holds one agent or a list. agents.yml wins on the same id. */
    public void scanDropins() {
        Map<String, AgentSpec> found = new LinkedHashMap<>();
        if (Files.isDirectory(dropinDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dropinDir, "*.{yml,yaml}")) {
                for (Path f : ds) {
                    try {
                        JsonNode n = YamlFiles.YAML.readTree(f.toFile());
                        List<JsonNode> items = new ArrayList<>();
                        if (n != null && n.isArray()) n.forEach(items::add);
                        else if (n != null && n.isObject()) items.add(n);
                        for (JsonNode item : items) {
                            AgentSpec a = YamlFiles.YAML.treeToValue(item, AgentSpec.class);
                            a.source = "drop-in";
                            a.normalize();
                            a.validate();
                            found.put(a.id, a);
                        }
                    } catch (IOException | RuntimeException e) {
                        log.warn("skipping drop-in {}: {}", f, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("cannot list {}: {}", dropinDir, e.getMessage());
            }
        }
        List<String> changed = new ArrayList<>();
        synchronized (this) {
            for (String id : new ArrayList<>(dropinOnly)) {
                if (!found.containsKey(id)) {
                    dropinOnly.remove(id);
                    agents.remove(id);
                    changed.add(id);
                }
            }
            for (AgentSpec a : found.values()) {
                AgentSpec cur = agents.get(a.id);
                if (cur == null) {
                    agents.put(a.id, a);
                    dropinOnly.add(a.id);
                    changed.add(a.id);
                    Audit.log("registry", "DROPIN_ADD", a.id + " " + target(a));
                } else if (dropinOnly.contains(a.id) && !sameConnection(cur, a)) {
                    agents.put(a.id, a);
                    changed.add(a.id);
                } else if (dropinOnly.contains(a.id)) {
                    a.createdAt = cur.createdAt;
                    agents.put(a.id, a);
                }
            }
        }
        for (String id : changed) {
            dropClient(id);
            synchronized (this) {
                if (!agents.containsKey(id)) states.remove(id);
            }
        }
    }

    private static boolean sameConnection(AgentSpec x, AgentSpec y) {
        return Objects.equals(x.transport, y.transport) && Objects.equals(x.url, y.url)
                && Objects.equals(x.tokenRef, y.tokenRef) && Objects.equals(x.command, y.command)
                && Objects.equals(x.env, y.env) && x.enabled == y.enabled;
    }

    /** Probes the configured discovery URLs; unknown servers that answer go to the inbox. */
    public void discover() {
        for (OrchestratorProperties.Endpoint ep : props.discovery().urls()) {
            if (ep == null || ep.url() == null || knownUrl(ep.url())) continue;
            AgentSpec a = new AgentSpec();
            a.transport = "http";
            a.url = ep.url();
            a.tokenRef = ep.tokenRef();
            a.source = "discovered";
            try {
                a.id = "tmp";
                Probe p = probe(a, null);
                a.id = uniqueId(slug(p.server().name().isBlank() ? URI.create(ep.url()).getHost() : p.server().name()));
                a.name = p.server().name();
                a.bio = firstSentence(p.server().instructions());
                addPending(a, "discovery", p, null);
            } catch (RuntimeException e) {
                log.debug("discovery {}: {}", ep.url(), e.getMessage());
            }
        }
    }

    private synchronized boolean knownUrl(String url) {
        return agents.values().stream().anyMatch(a -> url.equals(a.url))
                || pending.values().stream().anyMatch(p -> url.equals(p.spec.url));
    }

    // ------------------------------------------------------------------ self-registration inbox

    public record Registration(String id, String name, String role, String bio, String url, String token,
                               List<String> tags, String instructions) {}

    /** {@code POST /registry/register} from an agent. Known agent at the same URL: heartbeat. */
    public Map<String, Object> register(Registration r, String from) {
        AgentSpec a = new AgentSpec();
        a.id = r.id() == null ? null : r.id().strip().toLowerCase();
        a.name = r.name();
        a.role = r.role();
        a.bio = r.bio();
        a.url = r.url();
        a.tags = r.tags() == null ? new ArrayList<>() : new ArrayList<>(r.tags());
        a.instructions = r.instructions();
        a.transport = "http";                     // never stdio: a remote caller must not choose a local command
        a.source = "self-registered";
        a.normalize();
        a.validate();
        synchronized (this) {
            AgentSpec known = agents.get(a.id);
            if (known != null && a.url.equals(known.url)) {
                if (r.token() != null && !r.token().isBlank() && ("store:agent." + a.id).equals(known.tokenRef)) {
                    secrets.put("agent." + a.id, r.token().strip());
                }
                state(a.id).lastActive = Instant.now();
                refreshAsync(a.id);
                return Map.of("status", "registered", "id", a.id);
            }
        }
        Probe probe = null;
        String err = null;
        try {
            probe = probe(a, r.token());
        } catch (RuntimeException e) {
            err = message(e);
        }
        addPending(a, from, probe, err);
        if (r.token() != null && !r.token().isBlank()) {
            synchronized (this) {
                pending.get(a.id).spec.tokenRef = secrets.put("pending." + a.id, r.token().strip());
                savePending();
            }
        }
        Audit.log("registry", "REGISTER_REQUEST", a.id + " " + a.url + " from " + from);
        return Map.of("status", "pending-approval", "id", a.id);
    }

    private synchronized void addPending(AgentSpec a, String from, Probe probe, String err) {
        PendingAgent p = new PendingAgent();
        p.spec = a;
        p.from = from;
        p.receivedAt = Instant.now();
        p.probeError = err;
        if (probe != null) {
            p.serverName = probe.server().name();
            p.serverVersion = probe.server().version();
            p.tools = probe.tools().stream().map(McpTool::name).toList();
        }
        pending.put(a.id, p);
        savePending();
    }

    public synchronized List<PendingAgent> pending() {
        return List.copyOf(pending.values());
    }

    /** Moves an inbox entry onto the team, with any edits the reviewer made. */
    public AgentSpec approve(String id, AgentSpec edits) {
        PendingAgent p;
        synchronized (this) {
            p = pending.get(id);
            if (p == null) throw new NoSuchElementException("nothing pending with id " + id);
        }
        AgentSpec a = edits != null ? edits : p.spec;
        a.id = id;
        a.transport = "http";
        a.url = p.spec.url;
        a.source = p.spec.source;
        if (("store:pending." + id).equals(p.spec.tokenRef)) {
            secrets.rename("pending." + id, "agent." + id);
            a.tokenRef = "store:agent." + id;
        } else {
            a.tokenRef = p.spec.tokenRef;
        }
        AgentSpec saved = save(a, null, true);
        synchronized (this) {
            pending.remove(id);
            savePending();
        }
        Audit.log("ui", "REGISTER_APPROVE", id);
        return saved;
    }

    public void reject(String id) {
        synchronized (this) {
            if (pending.remove(id) == null) throw new NoSuchElementException("nothing pending with id " + id);
            savePending();
        }
        secrets.remove("pending." + id);
        Audit.log("ui", "REGISTER_REJECT", id);
    }

    private void savePending() {
        YamlFiles.write(pendingFile, List.copyOf(pending.values()), false);
    }

    // ------------------------------------------------------------------ helpers

    private synchronized String uniqueId(String base) {
        String id = base;
        for (int i = 2; agents.containsKey(id) || pending.containsKey(id); i++) id = base + "-" + i;
        return id;
    }

    static String slug(String s) {
        String x = s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (x.isEmpty()) x = "agent";
        return x.length() > 32 ? x.substring(0, 32) : x;
    }

    static String firstSentence(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        return dot > 0 && dot < 220 ? t.substring(0, dot + 1) : (t.length() > 220 ? t.substring(0, 220) + "..." : t);
    }

    static String target(AgentSpec a) {
        return "stdio".equals(a.transport) ? String.join(" ", a.command) : a.url;
    }

    public static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @PreDestroy
    void shutdown() {
        clients.keySet().forEach(this::dropClient);
    }

    /** Glob matching for confirm-tools and disabled-tools ({@code *} and {@code ?}). */
    public static final class Globs {
        private Globs() {}

        public static boolean any(List<String> patterns, String name) {
            if (patterns == null) return false;
            for (String p : patterns) {
                if (p == null || p.isBlank()) continue;
                StringBuilder re = new StringBuilder();
                for (char ch : p.strip().toCharArray()) {
                    switch (ch) {
                        case '*' -> re.append(".*");
                        case '?' -> re.append('.');
                        default -> re.append(Pattern.quote(String.valueOf(ch)));
                    }
                }
                if (name.matches(re.toString())) return true;
            }
            return false;
        }
    }
}
