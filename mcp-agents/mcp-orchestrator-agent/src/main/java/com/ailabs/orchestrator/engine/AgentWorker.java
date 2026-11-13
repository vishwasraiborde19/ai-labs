package com.ailabs.orchestrator.engine;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.llm.Llm;
import com.ailabs.orchestrator.llm.LlmService;
import com.ailabs.orchestrator.llm.Prompts;
import com.ailabs.orchestrator.mcp.McpServerInfo;
import com.ailabs.orchestrator.mcp.McpTool;
import com.ailabs.orchestrator.mcp.ToolCallResult;
import com.ailabs.orchestrator.registry.AgentRegistry;
import com.ailabs.orchestrator.registry.AgentSpec;
import com.ailabs.orchestrator.registry.AgentState;
import com.ailabs.orchestrator.run.RunContext;
import com.ailabs.orchestrator.store.Audit;
import com.ailabs.orchestrator.systems.SystemsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Runs a task on one agent: an LLM loop whose tools are that agent's MCP tools, with the agent's standing
 * instructions in the system prompt and human approval for its confirm-tools.
 */
@Service
public class AgentWorker {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int UI_DETAIL = 6000;

    private final AgentRegistry registry;
    private final LlmService llm;
    private final OrchestratorProperties props;
    private final SystemsService systems;

    public AgentWorker(AgentRegistry registry, LlmService llm, OrchestratorProperties props, SystemsService systems) {
        this.registry = registry;
        this.llm = llm;
        this.props = props;
        this.systems = systems;
    }

    /** Delegates {@code task} to the agent and returns its final report. */
    public String delegate(RunContext ctx, String agentId, String task) {
        AgentSpec a = registry.spec(agentId);
        AgentState st = registry.ensureOnline(agentId);
        List<McpTool> tools = registry.llmTools(agentId);
        ctx.event("delegate", agentId, a.displayName(), task);
        Audit.log("run " + ctx.run().id, "DELEGATE", agentId + " " + task);
        registry.markBusy(agentId, task);
        try {
            Llm.Request req = new Llm.Request(systemPrompt(a, st.server), task,
                    tools.stream().map(t -> new Llm.Tool(t.name(), t.description(), t.inputSchema())).toList(),
                    (tool, input) -> handle(ctx, a, tool, input),
                    ctx.listener(agentId), a.model, a.effort, props.limits().maxTurns());
            Llm.Result r = llm.provider(a.llmProvider).run(req);
            String report = r.text() == null || r.text().isBlank() ? "(the agent returned no report)" : r.text();
            ctx.event("answer", agentId, a.displayName() + " reported", report);
            return report;
        } finally {
            registry.markIdle(agentId);
        }
    }

    private Llm.Outcome handle(RunContext ctx, AgentSpec a, String tool, Map<String, Object> input) {
        ctx.checkCancelled();
        boolean known = registry.llmTools(a.id).stream().anyMatch(t -> t.name().equals(tool));
        if (!known) return Llm.Outcome.error("unknown or disabled tool " + tool);
        if (AgentRegistry.Globs.any(a.confirmTools, tool)
                && !ctx.approve(a.id, a.displayName() + " wants to run " + tool, pretty(input))) {
            return Llm.Outcome.error("The operator did not approve this call to " + tool
                    + ". Do not retry it; report what you needed it for.");
        }
        return callForLlm(ctx, a.id, tool, input);
    }

    private Llm.Outcome callForLlm(RunContext ctx, String agentId, String tool, Map<String, Object> input) {
        try {
            ToolCallResult r = call(ctx, agentId, tool, input);
            return new Llm.Outcome(limit(r.text()), r.error());
        } catch (RunContext.Cancelled e) {
            throw e;
        } catch (RuntimeException e) {
            return Llm.Outcome.error(AgentRegistry.message(e));
        }
    }

    /** Calls one tool and records it on the run's timeline (used by LLM loops, chains and the UI). */
    public ToolCallResult call(RunContext ctx, String agentId, String tool, Map<String, Object> input) {
        ctx.event("tool", agentId, tool, pretty(input));
        try {
            ToolCallResult r = registry.callTool(agentId, tool, input, "run " + ctx.run().id);
            ctx.event(r.error() ? "tool-error" : "tool-result", agentId, tool, clip(r.text()));
            return r;
        } catch (RuntimeException e) {
            ctx.event("tool-error", agentId, tool, AgentRegistry.message(e));
            throw e;
        }
    }

    String systemPrompt(AgentSpec a, McpServerInfo server) {
        String serverName = server == null || server.name().isBlank() ? a.id : server.name();
        StringBuilder sb = new StringBuilder(String.format(Prompts.AGENT, a.displayName(),
                a.role == null || a.role.isBlank() ? "" : ", " + a.role, serverName));
        if (server != null && server.instructions() != null && !server.instructions().isBlank()) {
            sb.append("\n\nNotes from the MCP server about its tools:\n").append(server.instructions().strip());
        }
        String sys = systems.promptForAgent(a.id);
        if (sys != null) sb.append("\n\n").append(sys);
        if (a.instructions != null && !a.instructions.isBlank()) {
            sb.append("\n\nStanding instructions from the operator (always follow these):\n").append(a.instructions.strip());
        }
        return sb.toString();
    }

    private String limit(String text) {
        int max = props.limits().maxToolResultChars();
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "\n\n[output cut: showing the first " + max + " of " + text.length()
                + " characters. Narrow the request (filters, a namespace, fewer lines) if you need the rest.]";
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() <= UI_DETAIL ? s : s.substring(0, UI_DETAIL) + "\n... (" + (s.length() - UI_DETAIL) + " more characters)";
    }

    static String pretty(Object o) {
        try {
            return PRETTY.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
