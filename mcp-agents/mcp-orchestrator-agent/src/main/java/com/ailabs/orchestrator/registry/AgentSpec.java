package com.ailabs.orchestrator.registry;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.ailabs.orchestrator.store.Secrets;
import com.ailabs.orchestrator.systems.SystemProbe;
import com.ailabs.orchestrator.store.YamlFiles;

/**
 * One agent in {@code agents.yml} (or a drop-in file in {@code agents.d/}): who it is on the team, how to reach
 * its MCP server, and the standing instructions the orchestrator gives it.
 */
public class AgentSpec {

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    // --- persona (shown on the tile)
    public String id;
    public String name;
    public String role;
    public String bio;
    /** Seed for the generated face; change it to get a different face. */
    public String avatar;
    public String color;
    public List<String> tags = new ArrayList<>();

    // --- connection
    /** {@code http} (streamable HTTP) or {@code stdio} (child process). */
    public String transport = "http";
    public String url;
    public String tokenRef;
    public List<String> command = new ArrayList<>();
    /** Extra environment for stdio agents; values starting with env:, path: or store: are resolved. */
    public Map<String, String> env = new LinkedHashMap<>();
    public String workdir;

    // --- behaviour
    /** Standing instructions, added to the agent's system prompt on every delegated task. */
    public String instructions;
    /** LLM provider name from settings (blank: the default provider). */
    public String llmProvider;
    public String model;
    public String effort;
    /** Glob patterns (e.g. {@code *_delete}, {@code k8s_drain}): LLM calls to these wait for a human approval. */
    public List<String> confirmTools = new ArrayList<>();
    /** Glob patterns of tools the LLM never sees for this agent (direct calls from the UI still work). */
    public List<String> disabledTools = new ArrayList<>();
    public boolean enabled = true;

    // --- systems
    /**
     * How to find the systems this agent works on (clusters, Jenkins servers, VMs). Null: built-in presets matched
     * to the agent's tools; an empty list: none.
     */
    public List<SystemProbe> systems;

    /** manual, drop-in, self-registered or discovered */
    public String source = "manual";
    public Instant createdAt;

    public AgentSpec copy() {
        return YamlFiles.YAML.convertValue(this, AgentSpec.class);
    }

    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }

    public void normalize() {
        if (tags == null) tags = new ArrayList<>();
        if (command == null) command = new ArrayList<>();
        if (env == null) env = new LinkedHashMap<>();
        if (confirmTools == null) confirmTools = new ArrayList<>();
        if (disabledTools == null) disabledTools = new ArrayList<>();
        if (transport == null || transport.isBlank()) transport = "http";
        if (avatar == null || avatar.isBlank()) avatar = id;
        if (createdAt == null) createdAt = Instant.now();
        tags.removeIf(t -> t == null || t.isBlank());
        confirmTools.removeIf(t -> t == null || t.isBlank());
        disabledTools.removeIf(t -> t == null || t.isBlank());
    }

    public void validate() {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be 1-40 lowercase letters, digits or dashes");
        }
        switch (transport) {
            case "http" -> {
                if (url == null || url.isBlank()) throw new IllegalArgumentException("an HTTP agent needs a URL");
                URI u;
                try {
                    u = URI.create(url.strip());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid URL: " + url);
                }
                if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()) || u.getHost() == null) {
                    throw new IllegalArgumentException("URL must be http(s)://host[:port]/path, e.g. http://127.0.0.1:8766/mcp");
                }
                url = url.strip();
            }
            case "stdio" -> {
                if (command == null || command.isEmpty() || command.get(0).isBlank()) {
                    throw new IllegalArgumentException("a stdio agent needs a command, e.g. java -jar /path/agent.jar");
                }
            }
            default -> throw new IllegalArgumentException("transport must be http or stdio");
        }
        Secrets.validateRef(tokenRef);
        if (systems != null) for (int i = 0; i < systems.size(); i++) systems.get(i).validate(i + 1);
        if (effort != null && !effort.isBlank() && !List.of("low", "medium", "high", "xhigh", "max").contains(effort)) {
            throw new IllegalArgumentException("effort must be low, medium, high, xhigh or max");
        }
    }
}
