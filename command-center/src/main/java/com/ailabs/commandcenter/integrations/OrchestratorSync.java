package com.ailabs.commandcenter.integrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ailabs.commandcenter.Audit;
import com.ailabs.commandcenter.catalog.CatalogStore;
import com.ailabs.commandcenter.catalog.Model.Access;
import com.ailabs.commandcenter.catalog.Model.Dependency;
import com.ailabs.commandcenter.catalog.Model.Link;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;
import com.ailabs.commandcenter.config.CcProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Imports the MCP orchestrator's agent registry into the catalog, so every agent shows up in the
 * command center without being entered twice. Imported entries have {@code source: orchestrator}
 * and id {@code agent-<orchestrator id>}; their description, links and tags follow the
 * orchestrator, while owner, access, usage and notes edited here are kept. Agents removed from the
 * orchestrator are removed here too.
 *
 * <p>The orchestrator's API only answers clients on its own machine, so this works when the
 * command center runs on the same host (the default local setup).
 */
@Component
public class OrchestratorSync {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorSync.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String SOURCE = "orchestrator";
    public static final String ORCHESTRATOR_ID = "orchestrator";

    public record Status(String state, String message, Integer agents, Instant at) {}

    private final CatalogStore store;
    private final CcProperties.Orchestrator cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private volatile Status last = new Status("never", "Not synced yet", null, null);

    public OrchestratorSync(CatalogStore store, CcProperties props) {
        this.store = store;
        this.cfg = props.orchestrator();
    }

    public Status status() {
        return last;
    }

    public String url() {
        return cfg.url();
    }

    @Scheduled(initialDelay = 3_000, fixedDelayString = "${commandcenter.orchestrator.interval:5m}")
    public void scheduled() {
        if (!cfg.autoSync()) {
            return;
        }
        try {
            sync();
        } catch (RuntimeException e) {
            log.debug("Orchestrator sync skipped: {}", e.getMessage());
        }
    }

    /** Fetches the agents and updates the catalog. */
    public synchronized Status sync() {
        JsonNode agents;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.url() + "/ui/api/agents"))
                    .timeout(Duration.ofSeconds(8)).header("X-Orchestrator", "1").header("Accept", "application/json").GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                String hint = r.statusCode() == 403 ? " (the orchestrator only answers clients on its own machine)" : "";
                last = new Status("error", "Orchestrator answered HTTP " + r.statusCode() + hint, null, Instant.now());
                throw new IllegalStateException(last.message());
            }
            agents = JSON.readTree(r.body());
        } catch (IOException e) {
            last = new Status("error", "Orchestrator not reachable at " + cfg.url() + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), null, Instant.now());
            throw new IllegalStateException(last.message(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }

        List<SystemEntry> imported = new ArrayList<>();
        for (JsonNode a : agents) {
            SystemEntry e = toEntry(a);
            if (e != null) {
                imported.add(e);
            }
        }
        int[] changes = new int[3];   // added, updated, removed
        store.updateSystems(current -> merge(current, imported, changes));
        String msg = String.format("%d agents: %d added, %d updated, %d removed", imported.size(), changes[0], changes[1], changes[2]);
        if (changes[0] + changes[1] + changes[2] > 0) {
            Audit.log("orchestrator-sync", msg);
        }
        last = new Status("ok", msg, imported.size(), Instant.now());
        return last;
    }

    /** Pure merge, also used by tests. */
    static List<SystemEntry> merge(List<SystemEntry> current, List<SystemEntry> imported, int[] changes) {
        Map<String, SystemEntry> byId = new LinkedHashMap<>();
        current.forEach(s -> byId.put(s.id(), s));
        boolean hasOrchestrator = byId.containsKey(ORCHESTRATOR_ID);
        Set<String> importedIds = imported.stream().map(SystemEntry::id).collect(Collectors.toSet());
        List<SystemEntry> out = new ArrayList<>();
        for (SystemEntry s : current) {
            if (SOURCE.equals(s.source()) && !importedIds.contains(s.id())) {
                changes[2]++;
                continue;   // gone from the orchestrator
            }
            out.add(s);
        }
        for (SystemEntry in : imported) {
            SystemEntry ex = byId.get(in.id());
            List<Dependency> deps = hasOrchestrator ? List.of(new Dependency(ORCHESTRATOR_ID, "registered with")) : List.of();
            if (ex == null) {
                out.add(in.withDependsOn(deps));
                changes[0]++;
                continue;
            }
            // keep what people curated here; take identity and links from the orchestrator
            List<Dependency> keptDeps = new ArrayList<>(ex.dependsOn());
            deps.forEach(d -> {
                if (keptDeps.stream().noneMatch(k -> k.to().equals(d.to()))) {
                    keptDeps.add(d);
                }
            });
            SystemEntry merged = new SystemEntry(in.id(), in.name(), ex.kind(), ex.category(), ex.environment() != null ? ex.environment() : in.environment(),
                    in.description(), ex.owner(), in.tags(), in.links(), ex.healthUrl(), ex.insecureTls(),
                    ex.access() != null ? ex.access() : in.access(), keptDeps, ex.usage(), ex.notes(), SOURCE);
            if (!merged.equals(ex)) {
                out.set(out.indexOf(ex), merged);
                changes[1]++;
            }
        }
        return out;
    }

    static SystemEntry toEntry(JsonNode view) {
        JsonNode a = view.path("agent");
        String id = a.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return null;
        }
        String cid = CatalogStore.slug("agent-" + id);
        String name = a.path("name").asText(id);
        String role = a.path("role").asText(null);
        String bio = a.path("bio").asText(null);
        String desc = role != null && bio != null ? role + ". " + bio : role != null ? role : bio;
        List<String> tags = new ArrayList<>();
        a.path("tags").forEach(t -> tags.add(t.asText()));
        tags.add("agent");
        List<Link> links = new ArrayList<>();
        String transport = a.path("transport").asText("http");
        String url = a.path("url").asText(null);
        if (url != null && !url.isBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            links.add(new Link("MCP endpoint", url, "mcp"));
        }
        Access access = new Access("token", null,
                a.path("tokenRef").asText(null) != null ? "orchestrator token ref: " + a.path("tokenRef").asText() : null,
                "stdio".equals(transport) ? "Started by the orchestrator over stdio; no network endpoint."
                        : "Bearer token on every MCP request. Add it to Claude Code with `claude mcp add --transport http`.");
        return new SystemEntry(cid, name, "mcp-agent", "agents", "lab", desc, null, tags, links, null, null,
                access, List.of(), null, null, SOURCE);
    }
}
