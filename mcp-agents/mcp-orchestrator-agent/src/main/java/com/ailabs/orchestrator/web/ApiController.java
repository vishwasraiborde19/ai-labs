package com.ailabs.orchestrator.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.orchestrator.chain.Chain;
import com.ailabs.orchestrator.chain.ChainService;
import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.engine.AgentWorker;
import com.ailabs.orchestrator.engine.Orchestrator;
import com.ailabs.orchestrator.llm.LlmService;
import com.ailabs.orchestrator.llm.LlmSettings;
import com.ailabs.orchestrator.llm.Prompts;
import com.ailabs.orchestrator.mcp.ToolCallResult;
import com.ailabs.orchestrator.registry.AgentRegistry;
import com.ailabs.orchestrator.registry.AgentSpec;
import com.ailabs.orchestrator.registry.AgentState;
import com.ailabs.orchestrator.run.Run;
import com.ailabs.orchestrator.run.RunService;
import com.ailabs.orchestrator.systems.SystemsService;

/** JSON API behind the web UI ({@code /ui/api}); loopback only, see {@link SecurityFilter}. */
@RestController
@RequestMapping("/ui/api")
public class ApiController {

    private final AgentRegistry registry;
    private final AgentWorker worker;
    private final Orchestrator orchestrator;
    private final ChainService chains;
    private final RunService runs;
    private final LlmService llm;
    private final OrchestratorProperties props;
    private final SystemsService systems;

    public ApiController(AgentRegistry registry, AgentWorker worker, Orchestrator orchestrator, ChainService chains,
                         RunService runs, LlmService llm, OrchestratorProperties props, SystemsService systems) {
        this.systems = systems;
        this.registry = registry;
        this.worker = worker;
        this.orchestrator = orchestrator;
        this.chains = chains;
        this.runs = runs;
        this.llm = llm;
        this.props = props;
    }

    public record AgentForm(AgentSpec agent, String token) {}

    public record TaskRequest(String task) {}

    public record ToolRequest(Map<String, Object> args) {}

    public record MissionRequest(String goal, String mode, List<String> agents) {}

    public record ChainRunRequest(Chain chain, String input) {}

    public record ApprovalRequest(boolean approve) {}

    public record SettingsForm(LlmSettings settings, Map<String, String> apiKeys) {}

    // ------------------------------------------------------------------ overview

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "0.1.0");
        m.put("home", props.homeDir().toString());
        m.put("registration", props.registration().enabled());
        m.put("discoveryUrls", props.discovery().urls().size());
        m.put("pending", registry.pending().size());
        m.put("defaultProvider", llm.settings().defaultProvider);
        m.put("defaultModel", llm.model(null, null));
        return m;
    }

    // ------------------------------------------------------------------ agents

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return registry.list().stream().map(this::view).toList();
    }

    @GetMapping("/agents/{id}")
    public Map<String, Object> agent(@PathVariable String id) {
        return view(registry.spec(id));
    }

    @PostMapping("/agents")
    public Map<String, Object> create(@RequestBody AgentForm f) {
        if (f.agent() == null) throw new IllegalArgumentException("agent is required");
        return view(registry.save(f.agent(), f.token(), true));
    }

    @PutMapping("/agents/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody AgentForm f) {
        if (f.agent() == null) throw new IllegalArgumentException("agent is required");
        f.agent().id = id;
        return view(registry.save(f.agent(), f.token(), false));
    }

    @DeleteMapping("/agents/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        registry.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping("/agents/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable String id) {
        registry.refresh(id);
        systems.discover(id, true);
        return view(registry.spec(id));
    }

    @PostMapping("/agents/probe")
    public Map<String, Object> probe(@RequestBody AgentForm f) {
        if (f.agent() == null) throw new IllegalArgumentException("agent is required");
        AgentSpec a = f.agent();
        if (a.id == null || a.id.isBlank()) a.id = "probe";
        AgentRegistry.Probe p = registry.probe(a, f.token());
        return Map.of("server", p.server(), "tools", p.tools());
    }

    /** Runs one tool directly (no LLM); recorded as a run. */
    @PostMapping("/agents/{id}/tools/{tool}")
    public Map<String, Object> callTool(@PathVariable String id, @PathVariable String tool, @RequestBody ToolRequest r) {
        registry.spec(id);
        Map<String, Object> args = r.args() == null ? Map.of() : r.args();
        Run run = runs.start("tool", id + " · " + tool, AgentWorkerJson.pretty(args), id, null, ctx -> {
            ToolCallResult res = worker.call(ctx, id, tool, args);
            if (res.error()) throw new IllegalStateException(res.text());
            return res.text();
        });
        return Map.of("runId", run.id);
    }

    /** Gives one agent a task; its LLM works with the agent's tools. */
    @PostMapping("/agents/{id}/delegate")
    public Map<String, Object> delegate(@PathVariable String id, @RequestBody TaskRequest r) {
        if (r.task() == null || r.task().isBlank()) throw new IllegalArgumentException("write a task");
        AgentSpec a = registry.spec(id);
        Run run = runs.start("delegate", a.displayName() + ": " + r.task(), r.task(), id, null,
                ctx -> worker.delegate(ctx, id, r.task().strip()));
        return Map.of("runId", run.id);
    }

    private Map<String, Object> view(AgentSpec a) {
        AgentState st = registry.state(a.id);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", a.enabled ? st.status : "disabled");
        s.put("error", st.error);
        s.put("server", st.server);
        s.put("tools", st.tools);
        s.put("lastCheck", st.lastCheck);
        s.put("lastActive", st.lastActive);
        s.put("toolsChanged", st.toolsChanged);
        s.put("busy", st.busy.get());
        s.put("currentTask", st.currentTask);
        s.put("calls", st.calls.get());
        s.put("failures", st.failures.get());
        s.put("tasks", st.tasks.get());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", a);
        m.put("state", s);
        m.put("dropin", registry.isDropin(a.id));
        m.put("model", llm.model(a.llmProvider, a.model));
        m.put("systems", systems.forAgent(a.id));
        m.put("systemsError", systems.error(a.id));
        m.put("probes", systems.probes(a));
        m.put("probesAuto", a.systems == null);
        return m;
    }

    // ------------------------------------------------------------------ systems

    /** Every system the team can reach, merged across agents (same endpoint = same system). */
    @GetMapping("/systems")
    public List<Map<String, Object>> systems() {
        return systems.grouped();
    }

    /** Re-reads systems and runs health checks now, for one agent or every online agent. */
    @PostMapping("/systems/check")
    public List<Map<String, Object>> checkSystems(@RequestParam(required = false) String agent) {
        for (AgentSpec a : registry.list()) {
            if (agent != null && !agent.isBlank() && !agent.equals(a.id)) continue;
            if (a.enabled && "online".equals(registry.state(a.id).status)) systems.discover(a.id, true);
        }
        return systems.grouped();
    }

    // ------------------------------------------------------------------ registry inbox

    @GetMapping("/registry/pending")
    public Object pending() {
        return registry.pending();
    }

    @PostMapping("/registry/pending/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) AgentForm f) {
        return view(registry.approve(id, f == null ? null : f.agent()));
    }

    @DeleteMapping("/registry/pending/{id}")
    public Map<String, Object> reject(@PathVariable String id) {
        registry.reject(id);
        return Map.of("rejected", id);
    }

    @PostMapping("/registry/scan")
    public Map<String, Object> scan() {
        registry.refreshAll();
        return Map.of("agents", registry.list().size(), "pending", registry.pending().size());
    }

    // ------------------------------------------------------------------ missions (orchestrator LLM)

    @PostMapping("/missions")
    public Map<String, Object> mission(@RequestBody MissionRequest r) {
        if (r.goal() == null || r.goal().isBlank()) throw new IllegalArgumentException("describe the goal");
        String goal = r.goal().strip();
        List<String> only = r.agents() == null ? List.of() : r.agents();
        Run run = "plan".equals(r.mode())
                ? runs.start("plan", "Plan: " + goal, goal, null, null, ctx -> {
                    Chain c = orchestrator.plan(ctx, goal, only);
                    return c.name + " (" + c.steps.size() + " steps). Review it, then run or save it as a chain.";
                })
                : runs.start("orchestrate", goal, goal, null, null, ctx -> orchestrator.orchestrate(ctx, goal, only));
        return Map.of("runId", run.id);
    }

    // ------------------------------------------------------------------ chains

    @GetMapping("/chains")
    public List<Chain> chains() {
        return chains.list();
    }

    @PostMapping("/chains")
    public Chain saveChain(@RequestBody Chain c) {
        return chains.save(c);
    }

    @PutMapping("/chains/{id}")
    public Chain updateChain(@PathVariable String id, @RequestBody Chain c) {
        chains.get(id);
        c.id = id;
        return chains.save(c);
    }

    @DeleteMapping("/chains/{id}")
    public Map<String, Object> deleteChain(@PathVariable String id) {
        chains.delete(id);
        return Map.of("deleted", id);
    }

    /** Runs a saved chain ({@code chain.id} set) or an unsaved one (e.g. a reviewed plan). */
    @PostMapping("/chains/run")
    public Map<String, Object> runChain(@RequestBody ChainRunRequest r) {
        Chain c = r.chain() != null && r.chain().id != null && !r.chain().id.isBlank() && (r.chain().steps == null || r.chain().steps.isEmpty())
                ? chains.get(r.chain().id) : r.chain();
        if (c == null) throw new IllegalArgumentException("chain is required");
        if (c.name == null || c.name.isBlank()) c.name = "Unsaved chain";
        c.validate();
        for (var s : c.steps) registry.spec(s.agent);
        String input = r.input() == null ? "" : r.input();
        Run run = runs.start("chain", c.name, input, null, c.id, ctx -> chains.run(ctx, c, input));
        return Map.of("runId", run.id);
    }

    // ------------------------------------------------------------------ runs

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Run r : runs.list(Math.min(limit, 500))) out.add(r.view(0, false));
        return out;
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable String id, @RequestParam(defaultValue = "0") int after) {
        return runs.get(id).view(after, true);
    }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        runs.cancel(id);
        return Map.of("cancelling", id);
    }

    @PostMapping("/runs/{id}/approvals/{approval}")
    public Map<String, Object> approval(@PathVariable String id, @PathVariable String approval, @RequestBody ApprovalRequest r) {
        runs.resolveApproval(id, approval, r.approve());
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", llm.settings());
        m.put("defaults", Map.of("orchestrator", Prompts.ORCHESTRATOR, "planner", Prompts.PLANNER));
        return m;
    }

    @PutMapping("/settings")
    public Object saveSettings(@RequestBody SettingsForm f) {
        if (f.settings() == null) throw new IllegalArgumentException("settings are required");
        return llm.save(f.settings(), f.apiKeys());
    }

    @PostMapping("/settings/test/{provider}")
    public Map<String, Object> test(@PathVariable String provider) {
        return llm.test(provider);
    }

    /** Small helper so the controller does not depend on AgentWorker internals for pretty JSON. */
    static final class AgentWorkerJson {
        private static final com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();

        static String pretty(Object o) {
            try {
                return M.writerWithDefaultPrettyPrinter().writeValueAsString(o);
            } catch (Exception e) {
                return String.valueOf(o);
            }
        }
    }
}
