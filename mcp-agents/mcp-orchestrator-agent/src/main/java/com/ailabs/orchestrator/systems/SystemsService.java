package com.ailabs.orchestrator.systems;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.mcp.McpTool;
import com.ailabs.orchestrator.mcp.ToolCallResult;
import com.ailabs.orchestrator.registry.AgentRegistry;
import com.ailabs.orchestrator.registry.AgentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Links agents to the systems they work on. On every refresh it calls each online agent's list tools (no LLM)
 * to learn its clusters, Jenkins servers, VMs...; every few minutes it runs the health tool per system
 * (e.g. {@code k8s_whoami}). The result feeds the UI, the agents' own prompts and the orchestrator's roster.
 */
@Service
public class SystemsService {

    private static final Logger log = LoggerFactory.getLogger(SystemsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENDPOINT_FIELDS = Set.of("apiServer", "url", "endpoint", "server");

    private final AgentRegistry registry;
    private final OrchestratorProperties props;
    private final Map<String, List<ManagedSystem>> byAgent = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastHealth = new ConcurrentHashMap<>();

    public SystemsService(AgentRegistry registry, OrchestratorProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** Probes in effect for an agent: its own {@code systems:} list, or presets matched to its tools. */
    public List<SystemProbe> probes(AgentSpec a) {
        if (a.systems != null) return a.systems;
        Set<String> tools = registry.state(a.id).tools.stream().map(McpTool::name).collect(Collectors.toSet());
        return Presets.forTools(tools);
    }

    public List<ManagedSystem> forAgent(String agentId) {
        return byAgent.getOrDefault(agentId, List.of());
    }

    public String error(String agentId) {
        return errors.get(agentId);
    }

    @Scheduled(initialDelay = 8000, fixedDelayString = "${orchestrator.limits.refresh-ms:60000}")
    public void scheduled() {
        long healthMs = props.limits().systemsHealthMs();
        byAgent.keySet().removeIf(id -> registry.list().stream().noneMatch(a -> a.id.equals(id)));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (AgentSpec a : registry.list()) {
                if (!a.enabled || !"online".equals(registry.state(a.id).status)) continue;
                Instant last = lastHealth.get(a.id);
                boolean health = last == null || last.plusMillis(healthMs).isBefore(Instant.now());
                pool.submit(() -> discover(a.id, health));
            }
        }
    }

    /** Re-reads the agent's systems now; with {@code health}, also checks each one. */
    public List<ManagedSystem> discover(String agentId, boolean health) {
        AgentSpec a = registry.spec(agentId);
        if (!a.enabled || !"online".equals(registry.state(agentId).status)) {
            byAgent.computeIfPresent(agentId, (k, list) -> list.stream()
                    .map(s -> s.withHealth("unknown", "agent " + registry.state(agentId).status)).toList());
            return forAgent(agentId);
        }
        Set<String> tools = registry.state(agentId).tools.stream().map(McpTool::name).collect(Collectors.toSet());
        Map<String, ManagedSystem> previous = new LinkedHashMap<>();
        for (ManagedSystem s : forAgent(agentId)) previous.put(s.kind() + "|" + s.name(), s);

        List<ManagedSystem> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> listCache = new LinkedHashMap<>();   // devops_list_resources serves three probes
        for (SystemProbe p : probes(a)) {
            if (p.listTool == null || !tools.contains(p.listTool)) continue;
            JsonNode root = listCache.get(p.listTool + p.listArgs);
            if (root == null) {
                try {
                    ToolCallResult r = registry.probeTool(agentId, p.listTool, p.listArgs == null ? Map.of() : p.listArgs);
                    if (r.error()) {
                        problems.add(p.listTool + ": " + clip(r.text(), 200));
                        continue;
                    }
                    root = JSON.readTree(r.text());
                    listCache.put(p.listTool + p.listArgs, root);
                } catch (IOException e) {
                    problems.add(p.listTool + " did not return JSON");
                    continue;
                } catch (RuntimeException e) {
                    problems.add(p.listTool + ": " + AgentRegistry.message(e));
                    continue;
                }
            }
            JsonNode items = at(root, p.listPath);
            if (!items.isArray()) {
                problems.add(p.listTool + (p.listPath == null ? "" : " at " + p.listPath) + " is not a list");
                continue;
            }
            for (JsonNode item : items) {
                ManagedSystem s = toSystem(agentId, p, item, previous.get(p.kind + "|" + item.path(p.nameField).asText()));
                if (s != null) out.add(s);
            }
        }
        if (health && !out.isEmpty()) {
            for (int i = 0; i < out.size(); i++) out.set(i, check(agentId, out.get(i), probeFor(a, out.get(i)), tools));
            lastHealth.put(agentId, Instant.now());
        }
        byAgent.put(agentId, List.copyOf(out));
        if (problems.isEmpty()) errors.remove(agentId); else errors.put(agentId, String.join("; ", problems));
        return forAgent(agentId);
    }

    private SystemProbe probeFor(AgentSpec a, ManagedSystem s) {
        for (SystemProbe p : probes(a)) if (p.kind.equals(s.kind())) return p;
        return null;
    }

    private ManagedSystem toSystem(String agentId, SystemProbe p, JsonNode item, ManagedSystem prev) {
        if (!item.isObject()) return null;
        String field = p.nameField == null || p.nameField.isBlank() ? "name" : p.nameField;
        String name = item.path(field).asText("");
        if (name.isBlank()) return null;
        String endpoint = null;
        Boolean readOnly = item.has("readOnly") ? item.get("readOnly").asBoolean() : null;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = item.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (k.equals(field) || k.equals("readOnly") || v.isNull()) continue;
            if (endpoint == null && ENDPOINT_FIELDS.contains(k) && v.isTextual()) {
                endpoint = v.asText();
                continue;
            }
            details.put(k, v.isValueNode() ? v.asText() : v.toString());
        }
        String status = "unknown", detail = null;
        Instant checked = null;
        if (p.stateField != null && item.hasNonNull(p.stateField)) {
            status = item.get(p.stateField).asText();
            checked = Instant.now();
        } else if (prev != null) {
            status = prev.status();
            detail = prev.statusDetail();
            checked = prev.checkedAt();
        }
        List<SystemProbe.QuickAction> actions = p.actions == null ? List.of() : p.actions.stream().map(qa -> {
            SystemProbe.QuickAction x = new SystemProbe.QuickAction();
            x.label = qa.label;
            x.tool = qa.tool;
            x.args = new LinkedHashMap<>();
            if (qa.args != null) qa.args.forEach((k, v) -> x.args.put(k, v instanceof String sv ? sv.replace("{{system}}", name) : v));
            return x;
        }).toList();
        return new ManagedSystem(key(p.kind, endpoint, name), p.kind, name, agentId, endpoint, readOnly, details,
                status, detail, checked, actions);
    }

    private ManagedSystem check(String agentId, ManagedSystem s, SystemProbe p, Set<String> tools) {
        if (p == null || p.healthTool == null || p.healthTool.isBlank() || !tools.contains(p.healthTool)) return s;
        try {
            ToolCallResult r = registry.probeTool(agentId, p.healthTool, Map.of(p.healthArg, s.name()));
            return r.error() ? s.withHealth("error", clip(r.text(), 300)) : s.withHealth("ok", summary(r.text()));
        } catch (RuntimeException e) {
            log.debug("health {} {}: {}", agentId, s.name(), e.getMessage());
            return s.withHealth("error", AgentRegistry.message(e));
        }
    }

    /** e.g. "v1.30.2 · system:serviceaccount:kube-agent:kube-agent" from k8s_whoami. */
    static String summary(String text) {
        try {
            JsonNode n = JSON.readTree(text);
            List<String> parts = new ArrayList<>();
            for (String f : List.of("version", "user", "platform", "message")) {
                if (n.hasNonNull(f) && !n.get(f).asText().isBlank()) parts.add(n.get(f).asText());
            }
            if (!parts.isEmpty()) return String.join(" · ", parts);
        } catch (IOException | RuntimeException ignored) {
            // not JSON
        }
        return clip(text, 160);
    }

    static JsonNode at(JsonNode root, String path) {
        JsonNode n = root;
        if (path != null && !path.isBlank()) for (String p : path.split("\\.")) n = n.path(p);
        return n;
    }

    static String key(String kind, String endpoint, String name) {
        String id = endpoint == null || endpoint.isBlank() ? "name:" + name
                : endpoint.strip().toLowerCase().replaceAll("/+$", "");
        return kind + "|" + id;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= n ? t : t.substring(0, n) + "...";
    }

    // ------------------------------------------------------------------ views

    /** Systems across the team, merged by key: one entry per real system with every agent that reaches it. */
    public List<Map<String, Object>> grouped() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (AgentSpec a : registry.list()) {
            for (ManagedSystem s : forAgent(a.id)) {
                Map<String, Object> g = out.computeIfAbsent(s.key(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", s.key());
                    m.put("kind", s.kind());
                    m.put("name", s.name());
                    m.put("endpoint", s.endpoint());
                    m.put("access", new ArrayList<ManagedSystem>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<ManagedSystem> access = (List<ManagedSystem>) g.get("access");
                access.add(s);
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(out.values());
        list.sort((x, y) -> (x.get("kind") + "|" + x.get("name")).compareTo(y.get("kind") + "|" + y.get("name")));
        return list;
    }

    /** For an agent's own system prompt. */
    public String promptForAgent(String agentId) {
        List<ManagedSystem> list = forAgent(agentId);
        if (list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Systems you can reach through your tools (use these names in tool arguments):");
        for (ManagedSystem s : list) sb.append("\n- ").append(line(s));
        return sb.toString();
    }

    /** One line per system for the orchestrator's roster. */
    public String rosterLine(String agentId) {
        List<ManagedSystem> list = forAgent(agentId);
        if (list.isEmpty()) return null;
        return list.stream().map(SystemsService::line).collect(Collectors.joining("; "));
    }

    private static String line(ManagedSystem s) {
        StringBuilder sb = new StringBuilder(s.kind()).append(" \"").append(s.name()).append('"');
        if (s.endpoint() != null) sb.append(" at ").append(s.endpoint());
        if (Boolean.TRUE.equals(s.readOnly())) sb.append(", read-only");
        if (!"unknown".equals(s.status())) {
            sb.append(", ").append(s.status());
            if (s.statusDetail() != null && !s.statusDetail().isBlank()) sb.append(" (").append(clip(s.statusDetail(), 120)).append(')');
        }
        return sb.toString();
    }
}
