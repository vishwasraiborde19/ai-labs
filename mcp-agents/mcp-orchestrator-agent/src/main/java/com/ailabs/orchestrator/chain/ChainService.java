package com.ailabs.orchestrator.chain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.engine.AgentWorker;
import com.ailabs.orchestrator.mcp.ToolCallResult;
import com.ailabs.orchestrator.registry.AgentRegistry;
import com.ailabs.orchestrator.run.RunContext;
import com.ailabs.orchestrator.store.Audit;
import com.ailabs.orchestrator.store.YamlFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Saved chains ({@code chains.yml}) and running them step by step. */
@Service
public class ChainService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Path file;
    private final Map<String, Chain> chains = new LinkedHashMap<>();
    private final AgentWorker worker;
    private final AgentRegistry registry;

    public ChainService(OrchestratorProperties props, AgentWorker worker, AgentRegistry registry) {
        this.file = props.homeDir().resolve("chains.yml");
        this.worker = worker;
        this.registry = registry;
        for (Chain c : YamlFiles.read(file, new TypeReference<List<Chain>>() {}, ArrayList::new)) chains.put(c.id, c);
    }

    public synchronized List<Chain> list() {
        return chains.values().stream().map(ChainService::copy)
                .sorted(Comparator.comparing((Chain c) -> c.name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public synchronized Chain get(String id) {
        Chain c = chains.get(id);
        if (c == null) throw new NoSuchElementException("no chain " + id);
        return copy(c);
    }

    public synchronized Chain save(Chain c) {
        c.validate();
        for (ChainStep s : c.steps) registry.spec(s.agent);   // agent must exist
        if (c.id == null || c.id.isBlank()) c.id = UUID.randomUUID().toString().substring(0, 8);
        c.updatedAt = Instant.now();
        chains.put(c.id, copy(c));
        YamlFiles.write(file, new ArrayList<>(chains.values()), false);
        Audit.log("ui", "CHAIN_SAVE", c.id + " " + c.name);
        return copy(c);
    }

    public synchronized void delete(String id) {
        if (chains.remove(id) == null) throw new NoSuchElementException("no chain " + id);
        YamlFiles.write(file, new ArrayList<>(chains.values()), false);
        Audit.log("ui", "CHAIN_DELETE", id);
    }

    /** Runs the steps in order; returns the last step's output. */
    public String run(RunContext ctx, Chain chain, String input) {
        chain.validate();
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < chain.steps.size(); i++) {
            ctx.checkCancelled();
            ChainStep s = chain.steps.get(i);
            int n = i + 1;
            String label = "Step " + n + " of " + chain.steps.size();
            String what = "delegate".equals(s.type) ? s.task : s.tool + " " + (s.args == null ? "{}" : s.args);
            ctx.event("step", s.agent, label, what);
            if (s.approve && !ctx.approve(s.agent, "Start " + label.toLowerCase() + " (" + s.agent + ")?", what)) {
                throw new IllegalStateException(label + " was not approved; the chain stopped");
            }
            try {
                String out;
                if ("delegate".equals(s.type)) {
                    out = worker.delegate(ctx, s.agent, Templates.render(s.task, input, outputs, false));
                } else {
                    String args = Templates.render(s.args == null || s.args.isBlank() ? "{}" : s.args, input, outputs, true);
                    Map<String, Object> parsed;
                    try {
                        parsed = JSON.readValue(args, MAP);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("step " + n + ": arguments are not a JSON object: " + e.getMessage());
                    }
                    ToolCallResult r = worker.call(ctx, s.agent, s.tool, parsed);
                    if (r.error()) throw new IllegalStateException(s.tool + " reported an error: " + r.text());
                    out = r.text();
                }
                outputs.add(out);
            } catch (RunContext.Cancelled e) {
                throw e;
            } catch (RuntimeException e) {
                String msg = AgentRegistry.message(e);
                if (!s.continueOnError) throw new IllegalStateException(label + " failed: " + msg, e);
                ctx.event("tool-error", s.agent, label + " failed, continuing", msg);
                outputs.add("ERROR: " + msg);
            }
        }
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1);
    }

    static Chain copy(Chain c) {
        return YamlFiles.YAML.convertValue(c, Chain.class);
    }
}
