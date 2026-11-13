package com.ailabs.kube.tools;

import static com.ailabs.kube.tools.Params.CLUSTER;
import static com.ailabs.kube.tools.Params.blank;
import static com.ailabs.kube.tools.Params.clamp;
import static com.ailabs.kube.tools.Params.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.kube.config.KubeProperties.Cluster;
import com.ailabs.kube.http.HttpSupport;
import com.ailabs.kube.k8s.ApiResource;
import com.ailabs.kube.k8s.Clusters;
import com.ailabs.kube.k8s.KubeClient;
import com.ailabs.kube.k8s.KubeException;
import com.ailabs.kube.k8s.KubeObjects;
import com.fasterxml.jackson.databind.JsonNode;

/** Skill: look after nodes and capacity: node status, resource usage, cordon and drain. */
@Component
public class NodeTools {

    private static final String METRICS = "/apis/metrics.k8s.io/v1beta1";

    private final Clusters clusters;

    public NodeTools(Clusters clusters) {
        this.clusters = clusters;
    }

    @Tool(name = "k8s_nodes", description = "Nodes with status, roles, version, IP, allocatable CPU/memory, taints and "
            + "pressure conditions, plus current CPU/memory usage when metrics-server is installed.")
    public List<Map<String, Object>> nodes(@ToolParam(required = false, description = CLUSTER) String cluster) {
        KubeClient k = clusters.client(cluster);
        Map<String, JsonNode> usage = new HashMap<>();
        try {
            k.get(METRICS + "/nodes").path("items").forEach(m -> usage.put(m.path("metadata").path("name").asText(), m.path("usage")));
        } catch (RuntimeException e) {
            // metrics-server not installed or not ready
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : k.get("/api/v1/nodes").path("items")) {
            Map<String, Object> row = new LinkedHashMap<>();
            String name = n.path("metadata").path("name").asText();
            row.put("name", name);
            row.putAll(KubeObjects.nodeRow(n));
            JsonNode u = usage.get(name);
            if (u != null) {
                row.put("usage", usageRow(u, n.path("status").path("allocatable")));
            }
            row.put("age", KubeObjects.age(n.path("metadata").path("creationTimestamp").asText()));
            out.add(row);
        }
        return out;
    }

    @Tool(name = "k8s_top", description = "Current CPU and memory usage of nodes or pods (kubectl top). Needs metrics-server.")
    public List<Map<String, Object>> top(@ToolParam(required = false, description = CLUSTER) String cluster,
                                         @ToolParam(required = false, description = "nodes or pods (default pods)") String what,
                                         @ToolParam(required = false, description = "Namespace for pods; omit for all allowed") String namespace,
                                         @ToolParam(required = false, description = "Sort by cpu (default) or memory") String sortBy,
                                         @ToolParam(required = false, description = "Max rows, default 30") Integer limit) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if ("nodes".equalsIgnoreCase(what) || "node".equalsIgnoreCase(what)) {
                Map<String, JsonNode> alloc = new HashMap<>();
                k.get("/api/v1/nodes").path("items").forEach(n -> alloc.put(n.path("metadata").path("name").asText(), n.path("status").path("allocatable")));
                for (JsonNode m : k.get(METRICS + "/nodes").path("items")) {
                    String name = m.path("metadata").path("name").asText();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("node", name);
                    row.putAll(usageRow(m.path("usage"), alloc.getOrDefault(name, HttpSupport.JSON.createObjectNode())));
                    out.add(row);
                }
            } else {
                for (String ns : clusters.scope(c, namespace)) {
                    String path = ns == null ? METRICS + "/pods" : METRICS + "/namespaces/" + HttpSupport.enc(ns) + "/pods";
                    for (JsonNode m : k.get(path).path("items")) {
                        double cpu = 0;
                        double mem = 0;
                        for (JsonNode ct : m.path("containers")) {
                            cpu += KubeObjects.cpuMilli(ct.path("usage").path("cpu").asText());
                            mem += KubeObjects.memMi(ct.path("usage").path("memory").asText());
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("namespace", m.path("metadata").path("namespace").asText());
                        row.put("pod", m.path("metadata").path("name").asText());
                        row.put("cpuMilli", Math.round(cpu));
                        row.put("memoryMi", Math.round(mem));
                        out.add(row);
                    }
                }
            }
        } catch (KubeException e) {
            if (e.status() == 404 || e.status() == 503) {
                throw new IllegalStateException("Metrics are not available on cluster '" + k.name()
                        + "': metrics-server is not installed or not ready (" + e.status() + ")");
            }
            throw e;
        }
        String key = "memory".equalsIgnoreCase(sortBy) ? "memoryMi" : "cpuMilli";
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get(key)).longValue()).reversed());
        int max = clamp(limit, 30, 1, 500);
        return out.size() > max ? out.subList(0, max) : out;
    }

    @Tool(name = "k8s_cordon", description = "Mark a node unschedulable (cordon) or schedulable again (uncordon=true). "
            + "Needs cluster-scoped-writes in the agent config.")
    public Map<String, Object> cordon(@ToolParam(required = false, description = CLUSTER) String cluster,
                                      @ToolParam(description = "Node name") String node,
                                      @ToolParam(required = false, description = "true to uncordon") Boolean uncordon) {
        KubeClient k = writableNodes(cluster, node);
        boolean unschedulable = !Boolean.TRUE.equals(uncordon);
        k.patch("/api/v1/nodes/" + HttpSupport.enc(node), "application/merge-patch+json",
                "{\"spec\":{\"unschedulable\":" + (unschedulable ? "true" : "null") + "}}");
        return Map.of("node", node, "state", unschedulable ? "cordoned" : "uncordoned");
    }

    @Tool(name = "k8s_drain", description = "Drain a node for maintenance (kubectl drain): cordon it, then evict its pods "
            + "while respecting PodDisruptionBudgets. DaemonSet and static pods stay. confirm must repeat the node name. "
            + "Run with dryRun=true first to see what would be evicted and what blocks the drain.")
    public Map<String, Object> drain(@ToolParam(required = false, description = CLUSTER) String cluster,
                                     @ToolParam(description = "Node name") String node,
                                     @ToolParam(description = "Repeat the node name to confirm") String confirm,
                                     @ToolParam(required = false, description = "Only report the plan") Boolean dryRun,
                                     @ToolParam(required = false, description = "Also evict pods using emptyDir (their data is lost)") Boolean deleteEmptyDirData,
                                     @ToolParam(required = false, description = "Also evict pods without a controller (they are not recreated)") Boolean force,
                                     @ToolParam(required = false, description = "Seconds to wait for evictions, default 300, max 1800") Integer timeoutSeconds) {
        if (blank(node) || !node.equals(confirm)) {
            throw new IllegalArgumentException("Refusing to drain: confirm must equal the node name '" + node + "'");
        }
        KubeClient k = writableNodes(cluster, node);
        k.get("/api/v1/nodes/" + HttpSupport.enc(node)); // 404 early for a typo

        List<JsonNode> toEvict = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (JsonNode p : k.get("/api/v1/pods" + query("fieldSelector", "spec.nodeName=" + node)).path("items")) {
            String id = p.path("metadata").path("namespace").asText() + "/" + p.path("metadata").path("name").asText();
            String phase = p.path("status").path("phase").asText();
            if (p.path("metadata").path("annotations").has("kubernetes.io/config.mirror")) {
                skipped.add(id + " (static pod)");
                continue;
            }
            String owner = "";
            for (JsonNode ref : p.path("metadata").path("ownerReferences")) {
                if (ref.path("controller").asBoolean(false)) {
                    owner = ref.path("kind").asText();
                }
            }
            if (owner.equals("DaemonSet")) {
                skipped.add(id + " (DaemonSet)");
                continue;
            }
            boolean finished = phase.equals("Succeeded") || phase.equals("Failed");
            if (owner.isEmpty() && !finished && !Boolean.TRUE.equals(force)) {
                blockers.add(id + ": no controller, would not be recreated (pass force=true)");
            }
            boolean emptyDir = false;
            for (JsonNode v : p.path("spec").path("volumes")) {
                emptyDir |= v.has("emptyDir");
            }
            if (emptyDir && !finished && !Boolean.TRUE.equals(deleteEmptyDirData)) {
                blockers.add(id + ": uses emptyDir, its data would be lost (pass deleteEmptyDirData=true)");
            }
            toEvict.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        List<String> plan = toEvict.stream()
                .map(p -> p.path("metadata").path("namespace").asText() + "/" + p.path("metadata").path("name").asText()).toList();
        if (!blockers.isEmpty() || Boolean.TRUE.equals(dryRun)) {
            out.put("state", !blockers.isEmpty() ? "blocked, nothing changed" : "dry run, nothing changed");
            out.put("wouldEvict", plan);
            out.put("staysOnNode", skipped);
            if (!blockers.isEmpty()) {
                out.put("blockers", blockers);
            }
            return out;
        }

        k.patch("/api/v1/nodes/" + HttpSupport.enc(node), "application/merge-patch+json", "{\"spec\":{\"unschedulable\":true}}");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(clamp(timeoutSeconds, 300, 10, 1800)));
        List<JsonNode> pending = new ArrayList<>(toEvict);
        Map<String, String> lastError = new LinkedHashMap<>();
        List<String> evicted = new ArrayList<>();
        while (!pending.isEmpty() && Instant.now().isBefore(deadline)) {
            List<JsonNode> retry = new ArrayList<>();
            for (JsonNode p : pending) {
                String ns = p.path("metadata").path("namespace").asText();
                String name = p.path("metadata").path("name").asText();
                String body = "{\"apiVersion\":\"policy/v1\",\"kind\":\"Eviction\",\"metadata\":{\"name\":\"" + name
                        + "\",\"namespace\":\"" + ns + "\"}}";
                try {
                    k.post("/api/v1/namespaces/" + HttpSupport.enc(ns) + "/pods/" + HttpSupport.enc(name) + "/eviction", body);
                    evicted.add(ns + "/" + name);
                    lastError.remove(ns + "/" + name);
                } catch (KubeException e) {
                    if (e.status() == 404) {
                        evicted.add(ns + "/" + name + " (already gone)");
                    } else {
                        lastError.put(ns + "/" + name, e.getMessage());
                        retry.add(p); // 429: a PodDisruptionBudget blocks it for now
                    }
                }
            }
            pending = retry;
            if (!pending.isEmpty()) {
                Params.sleep(5000);
            }
        }
        // wait for evicted pods to terminate
        while (Instant.now().isBefore(deadline)) {
            long left = k.get("/api/v1/pods" + query("fieldSelector", "spec.nodeName=" + node)).path("items").size();
            if (left <= skipped.size()) {
                break;
            }
            Params.sleep(3000);
        }
        out.put("state", pending.isEmpty() ? "drained (cordoned; uncordon with k8s_cordon uncordon=true)" : "partially drained, timed out");
        out.put("evicted", evicted);
        out.put("staysOnNode", skipped);
        if (!pending.isEmpty()) {
            out.put("notEvicted", lastError);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private KubeClient writableNodes(String cluster, String node) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource nodes = k.catalog().resolve("nodes", null);
        clusters.checkWrite(c, nodes, null, node);
        return k;
    }

    static Map<String, Object> usageRow(JsonNode usage, JsonNode allocatable) {
        double cpu = KubeObjects.cpuMilli(usage.path("cpu").asText());
        double mem = KubeObjects.memMi(usage.path("memory").asText());
        double cpuCap = KubeObjects.cpuMilli(allocatable.path("cpu").asText());
        double memCap = KubeObjects.memMi(allocatable.path("memory").asText());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpuMilli", Math.round(cpu));
        if (cpuCap > 0) {
            m.put("cpuPercent", Math.round(cpu * 100 / cpuCap));
        }
        m.put("memoryMi", Math.round(mem));
        if (memCap > 0) {
            m.put("memoryPercent", Math.round(mem * 100 / memCap));
        }
        return m;
    }
}
