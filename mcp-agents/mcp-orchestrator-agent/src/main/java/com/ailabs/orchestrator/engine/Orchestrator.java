package com.ailabs.orchestrator.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.chain.Chain;
import com.ailabs.orchestrator.chain.ChainStep;
import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.llm.Llm;
import com.ailabs.orchestrator.llm.LlmService;
import com.ailabs.orchestrator.llm.LlmSettings;
import com.ailabs.orchestrator.llm.Prompts;
import com.ailabs.orchestrator.mcp.McpTool;
import com.ailabs.orchestrator.registry.AgentRegistry;
import com.ailabs.orchestrator.registry.AgentSpec;
import com.ailabs.orchestrator.registry.AgentState;
import com.ailabs.orchestrator.run.RunContext;
import com.ailabs.orchestrator.systems.SystemsService;

/**
 * The orchestrator's own LLM: either works a goal by delegating to agents ({@code delegate_to_<agent>} tools), or
 * drafts a plan (a chain) for a person to review before anything runs.
 */
@Service
public class Orchestrator {

    private final AgentRegistry registry;
    private final AgentWorker worker;
    private final LlmService llm;
    private final OrchestratorProperties props;
    private final SystemsService systems;

    public Orchestrator(AgentRegistry registry, AgentWorker worker, LlmService llm, OrchestratorProperties props,
                        SystemsService systems) {
        this.registry = registry;
        this.worker = worker;
        this.llm = llm;
        this.props = props;
        this.systems = systems;
    }

    /** Agents that are enabled and reachable now, optionally limited to {@code only}. */
    private List<AgentSpec> roster(List<String> only) {
        List<AgentSpec> out = new ArrayList<>();
        for (AgentSpec a : registry.list()) {
            if (!a.enabled || (only != null && !only.isEmpty() && !only.contains(a.id))) continue;
            try {
                registry.ensureOnline(a.id);
                out.add(a);
            } catch (RuntimeException ignored) {
                // offline agents are left out of the roster
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("no agents are online" + (only == null || only.isEmpty() ? "" : " among " + only));
        return out;
    }

    private String rosterText(List<AgentSpec> agents) {
        StringBuilder sb = new StringBuilder("Your team:\n");
        for (AgentSpec a : agents) {
            AgentState st = registry.state(a.id);
            sb.append("\n- ").append(a.id).append(": ").append(a.displayName());
            if (a.role != null && !a.role.isBlank()) sb.append(", ").append(a.role);
            sb.append('.');
            if (a.bio != null && !a.bio.isBlank()) sb.append(' ').append(a.bio.strip());
            if (!a.tags.isEmpty()) sb.append(" Tags: ").append(String.join(", ", a.tags)).append('.');
            String sys = systems.rosterLine(a.id);
            if (sys != null) sb.append("\n  Systems: ").append(sys);
            List<McpTool> tools = registry.llmTools(a.id);
            sb.append("\n  Tools: ").append(String.join(", ", tools.stream().map(McpTool::name).toList()));
            if (st.server != null && st.server.instructions() != null && !st.server.instructions().isBlank()) {
                sb.append("\n  About: ").append(st.server.instructions().strip().replaceAll("\\s+", " "));
            }
        }
        return sb.toString();
    }

    /** Works the goal by delegating; returns the orchestrator's final summary. */
    public String orchestrate(RunContext ctx, String goal, List<String> only) {
        LlmSettings s = llm.settings();
        List<AgentSpec> team = roster(only);
        Map<String, String> toolToAgent = new HashMap<>();
        List<Llm.Tool> tools = new ArrayList<>();
        for (AgentSpec a : team) {
            String name = "delegate_to_" + a.id.replace('-', '_');
            toolToAgent.put(name, a.id);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("task", Map.of("type", "string", "description",
                    "Complete, self-contained directive for " + a.displayName() + ": objective, exact names, what to report."));
            tools.add(new Llm.Tool(name, "Give a task to " + a.displayName()
                    + (a.role == null || a.role.isBlank() ? "" : " (" + a.role + ")")
                    + " and wait for the report. " + (a.bio == null ? "" : a.bio)
                    + (systems.rosterLine(a.id) == null ? "" : " Systems: " + systems.rosterLine(a.id)),
                    Map.of("type", "object", "properties", params, "required", List.of("task"))));
        }
        String system = (blank(s.orchestratorInstructions) ? Prompts.ORCHESTRATOR : s.orchestratorInstructions.strip())
                + "\n\n" + rosterText(team);
        ctx.event("plan", null, "Orchestrator is working on the goal", "Team: " + String.join(", ", team.stream().map(AgentSpec::displayName).toList()));
        Llm.Result r = llm.provider(s.orchestratorProvider).run(new Llm.Request(system, goal, tools, (tool, input) -> {
            String agent = toolToAgent.get(tool);
            if (agent == null) return Llm.Outcome.error("unknown tool " + tool);
            String task = String.valueOf(input.getOrDefault("task", "")).strip();
            if (task.isEmpty()) return Llm.Outcome.error("task is empty");
            try {
                return Llm.Outcome.ok("Report from " + agent + ":\n" + worker.delegate(ctx, agent, task));
            } catch (RunContext.Cancelled e) {
                throw e;
            } catch (RuntimeException e) {
                ctx.event("tool-error", agent, "Delegation failed", AgentRegistry.message(e));
                return Llm.Outcome.error(agent + " could not do this: " + AgentRegistry.message(e));
            }
        }, ctx.listener(null), s.orchestratorModel, null, props.limits().maxTurns()));
        return r.text();
    }

    /** Drafts a chain for the goal without running anything; the chain is stored as the run's data. */
    public Chain plan(RunContext ctx, String goal, List<String> only) {
        LlmSettings s = llm.settings();
        List<AgentSpec> team = roster(only);
        List<String> ids = team.stream().map(a -> a.id).toList();
        Map<String, Object> step = Map.of("type", "object", "properties", Map.of(
                        "agent", Map.of("type", "string", "enum", ids, "description", "Agent id from the roster"),
                        "task", Map.of("type", "string", "description", "Self-contained directive; may use {{prev}} or {{stepN}}"),
                        "why", Map.of("type", "string", "description", "One line: why this step")),
                "required", List.of("agent", "task"));
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Short name for the plan"),
                        "summary", Map.of("type", "string", "description", "Two or three sentences for the operator"),
                        "steps", Map.of("type", "array", "items", step, "minItems", 1)),
                "required", List.of("name", "steps"));
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        String system = (blank(s.plannerInstructions) ? Prompts.PLANNER : s.plannerInstructions.strip()) + "\n\n" + rosterText(team);
        ctx.event("plan", null, "Drafting a plan", null);
        llm.provider(s.orchestratorProvider).run(new Llm.Request(system, goal,
                List.of(new Llm.Tool("propose_plan", "Submit the plan for the operator to review.", schema)),
                (tool, input) -> {
                    if (!"propose_plan".equals(tool)) return Llm.Outcome.error("unknown tool " + tool);
                    captured.set(input);
                    return Llm.Outcome.ok("Plan recorded. The operator will review it. Stop here.");
                }, ctx.listener(null), s.orchestratorModel, null, 4));
        Map<String, Object> p = captured.get();
        if (p == null) throw new IllegalStateException("the planner did not propose a plan");
        Chain c = new Chain();
        c.name = String.valueOf(p.getOrDefault("name", "Plan"));
        c.description = p.get("summary") == null ? goal : String.valueOf(p.get("summary"));
        if (p.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                if (!(o instanceof Map<?, ?> m)) continue;
                ChainStep cs = new ChainStep();
                cs.type = "delegate";
                cs.agent = String.valueOf(m.get("agent"));
                cs.task = String.valueOf(m.get("task"));
                cs.note = m.get("why") == null ? null : String.valueOf(m.get("why"));
                if (ids.contains(cs.agent)) c.steps.add(cs);
            }
        }
        if (c.steps.isEmpty()) throw new IllegalStateException("the plan has no usable steps");
        ctx.event("answer", null, "Plan: " + c.name, describe(c));
        ctx.run().data = c;
        return c;
    }

    static String describe(Chain c) {
        StringBuilder sb = new StringBuilder(c.description == null ? "" : c.description + "\n");
        for (int i = 0; i < c.steps.size(); i++) {
            ChainStep s = c.steps.get(i);
            sb.append('\n').append(i + 1).append(". [").append(s.agent).append("] ").append(s.task);
        }
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
