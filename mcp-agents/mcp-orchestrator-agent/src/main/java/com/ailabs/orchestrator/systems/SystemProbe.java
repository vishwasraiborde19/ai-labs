package com.ailabs.orchestrator.systems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the orchestrator finds the systems an agent works on (clusters, Jenkins servers, VMs...) by calling the
 * agent's own tools directly, without an LLM. Known agents get presets ({@link Presets}); any agent can declare
 * its own in {@code systems:} of its agents.yml / drop-in entry.
 *
 * <pre>
 * systems:
 *   - kind: kubernetes
 *     list-tool: k8s_clusters        # returns a JSON array of objects (or an object holding one, see list-path)
 *     name-field: name
 *     health-tool: k8s_whoami        # called per system with {health-arg: name}
 *     health-arg: cluster
 *     actions:
 *       - {label: Nodes, tool: k8s_nodes, args: {cluster: "{{system}}"}}
 * </pre>
 */
public class SystemProbe {

    /** kubernetes, jenkins, dockerhub, vm, or anything else. */
    public String kind;
    public String listTool;
    public Map<String, Object> listArgs = new LinkedHashMap<>();
    /** Dot path into the list tool's JSON result (blank: the result itself). */
    public String listPath;
    public String nameField = "name";
    /** Field of each item that already carries live state (e.g. {@code state} from fleet_hosts). */
    public String stateField;
    public String healthTool;
    public String healthArg;
    /** One-click tool calls shown next to each system; string values may use {@code {{system}}}. */
    public List<QuickAction> actions = new ArrayList<>();

    public static class QuickAction {
        public String label;
        public String tool;
        public Map<String, Object> args = new LinkedHashMap<>();

        public QuickAction() {}

        QuickAction(String label, String tool, Map<String, Object> args) {
            this.label = label;
            this.tool = tool;
            this.args = new LinkedHashMap<>(args);
        }
    }

    public void validate(int n) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("systems[" + n + "]: kind is required");
        if (listTool == null || listTool.isBlank()) throw new IllegalArgumentException("systems[" + n + "]: list-tool is required");
        if (healthTool != null && !healthTool.isBlank() && (healthArg == null || healthArg.isBlank())) {
            throw new IllegalArgumentException("systems[" + n + "]: health-arg is required with health-tool");
        }
    }
}
