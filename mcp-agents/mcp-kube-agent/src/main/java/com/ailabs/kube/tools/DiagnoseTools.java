package com.ailabs.kube.tools;

import static com.ailabs.kube.tools.Params.CLUSTER;
import static com.ailabs.kube.tools.Params.blank;
import static com.ailabs.kube.tools.Params.clamp;
import static com.ailabs.kube.tools.Params.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.kube.config.KubeProperties.Cluster;
import com.ailabs.kube.http.HttpSupport;
import com.ailabs.kube.k8s.Clusters;
import com.ailabs.kube.k8s.KubeClient;
import com.ailabs.kube.k8s.KubeObjects;
import com.fasterxml.jackson.databind.JsonNode;

/** Skill: find out why something is broken: logs, events and a cluster health sweep. */
@Component
public class DiagnoseTools {

    private static final int MAX_LOG_CHARS = 120_000;
    private static final Map<String, String> HINTS = Map.of(
            "CrashLoopBackOff", "the container keeps exiting: k8s_logs with previous=true shows the last crash",
            "ImagePullBackOff", "image cannot be pulled: check the image name and tag, registry reachability and imagePullSecrets",
            "ErrImagePull", "image cannot be pulled: check the image name and tag, registry reachability and imagePullSecrets",
            "CreateContainerConfigError", "a ConfigMap, Secret or key referenced by env/volumes is missing",
            "OOMKilled", "the container hit its memory limit: raise resources.limits.memory or fix the leak",
            "Pending", "not scheduled or volumes not ready: k8s_describe the pod and read its FailedScheduling / FailedMount events",
            "ContainerCreating", "stuck creating: usually a volume mount or CNI problem; read the pod's events",
            "Error", "the container exited with an error: read k8s_logs (previous=true if it restarted)",
            "Evicted", "the node ran short of resources (see the pod's status message); the controller will replace it");

    private final Clusters clusters;

    public DiagnoseTools(Clusters clusters) {
        this.clusters = clusters;
    }

    @Tool(name = "k8s_logs", description = "Container logs of one pod, or of the pods matching a label selector. "
            + "previous=true reads the last crashed run. grep keeps only lines containing the text (case-insensitive).")
    public String logs(@ToolParam(required = false, description = CLUSTER) String cluster,
                       @ToolParam(description = "Namespace") String namespace,
                       @ToolParam(required = false, description = "Pod name") String pod,
                       @ToolParam(required = false, description = "Label selector instead of a pod name, e.g. app=web") String labelSelector,
                       @ToolParam(required = false, description = "Container name if the pod has several") String container,
                       @ToolParam(required = false, description = "Lines from the end, default 200, max 5000") Integer tailLines,
                       @ToolParam(required = false, description = "Logs of the previous (crashed) container instance") Boolean previous,
                       @ToolParam(required = false, description = "Only lines from the last N seconds") Integer sinceSeconds,
                       @ToolParam(required = false, description = "Keep only lines containing this text") String grep,
                       @ToolParam(required = false, description = "Max pods for a selector, default 3, max 10") Integer maxPods) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        if (blank(namespace)) {
            throw new IllegalArgumentException("namespace is required");
        }
        clusters.checkRead(c, null, namespace);
        String nsPath = "/api/v1/namespaces/" + HttpSupport.enc(namespace) + "/pods";
        List<String> pods = new ArrayList<>();
        if (!blank(pod)) {
            pods.add(pod);
        } else if (!blank(labelSelector)) {
            k.get(nsPath + query("labelSelector", labelSelector)).path("items")
                    .forEach(p -> pods.add(p.path("metadata").path("name").asText()));
            if (pods.isEmpty()) {
                return "No pods match " + labelSelector + " in " + namespace;
            }
        } else {
            throw new IllegalArgumentException("pass pod or labelSelector");
        }
        int limit = clamp(maxPods, 3, 1, 10);
        String q = query("container", container, "tailLines", String.valueOf(clamp(tailLines, 200, 1, 5000)),
                "previous", Boolean.TRUE.equals(previous) ? "true" : null,
                "sinceSeconds", sinceSeconds == null || sinceSeconds <= 0 ? null : String.valueOf(sinceSeconds));
        StringBuilder out = new StringBuilder();
        for (String p : pods.subList(0, Math.min(limit, pods.size()))) {
            String text;
            try {
                text = k.text(nsPath + "/" + HttpSupport.enc(p) + "/log" + q);
            } catch (RuntimeException e) {
                text = "(" + e.getMessage() + ")\n";
            }
            if (!blank(grep)) {
                String needle = grep.toLowerCase(Locale.ROOT);
                StringBuilder kept = new StringBuilder();
                text.lines().filter(l -> l.toLowerCase(Locale.ROOT).contains(needle)).forEach(l -> kept.append(l).append('\n'));
                text = kept.toString();
            }
            if (pods.size() > 1) {
                out.append("==> ").append(p).append(" <==\n");
            }
            out.append(text.isEmpty() ? "(no log lines)\n" : text);
        }
        if (pods.size() > limit) {
            out.append("\n(").append(pods.size() - limit).append(" more pods match; raise maxPods or narrow the selector)\n");
        }
        return out.length() > MAX_LOG_CHARS ? "...(truncated)\n" + out.substring(out.length() - MAX_LOG_CHARS) : out.toString();
    }

    @Tool(name = "k8s_events", description = "Recent events, newest first: in one namespace or across all allowed ones, "
            + "optionally for one object, warnings only, or within the last N minutes.")
    public List<Map<String, Object>> events(@ToolParam(required = false, description = CLUSTER) String cluster,
                                            @ToolParam(required = false, description = "Namespace; omit for all allowed namespaces") String namespace,
                                            @ToolParam(required = false, description = "Object name (pod, deployment, node, ...)") String objectName,
                                            @ToolParam(required = false, description = "Object kind, e.g. Pod, Deployment, Node") String objectKind,
                                            @ToolParam(required = false, description = "Only type=Warning events") Boolean warningsOnly,
                                            @ToolParam(required = false, description = "Only events from the last N minutes") Integer sinceMinutes,
                                            @ToolParam(required = false, description = "Max events, default 50, max 500") Integer limit) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        List<String> fields = new ArrayList<>();
        if (!blank(objectName)) {
            fields.add("involvedObject.name=" + objectName);
        }
        if (!blank(objectKind)) {
            fields.add("involvedObject.kind=" + objectKind);
        }
        if (Boolean.TRUE.equals(warningsOnly)) {
            fields.add("type=Warning");
        }
        Instant since = sinceMinutes == null || sinceMinutes <= 0 ? null : Instant.now().minus(Duration.ofMinutes(sinceMinutes));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ns : clusters.scope(c, namespace)) {
            String base = ns == null ? "/api/v1/events" : "/api/v1/namespaces/" + HttpSupport.enc(ns) + "/events";
            for (JsonNode e : k.get(base + query("fieldSelector", String.join(",", fields))).path("items")) {
                Map<String, Object> row = eventRow(e);
                if (since == null || after((String) row.get("time"), since)) {
                    out.add(row);
                }
            }
        }
        out.sort((a, b) -> ((String) b.get("time")).compareTo((String) a.get("time")));
        int max = clamp(limit, 50, 1, 500);
        return out.size() > max ? out.subList(0, max) : out;
    }

    @Tool(name = "k8s_troubleshoot", description = "Health sweep of a namespace (or every allowed namespace): unhealthy "
            + "nodes, failing or restarting pods with hints, workloads short of replicas, unbound PVCs and the recent "
            + "warning events. Start here when something is broken.")
    public Map<String, Object> troubleshoot(@ToolParam(required = false, description = CLUSTER) String cluster,
                                            @ToolParam(required = false, description = "Namespace; omit for all allowed namespaces") String namespace,
                                            @ToolParam(required = false, description = "Warning events from the last N minutes, default 60") Integer eventMinutes) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        List<String> scopes = clusters.scope(c, namespace);
        Map<String, Object> out = new LinkedHashMap<>();
        int problems = 0;

        if (blank(namespace)) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (JsonNode n : k.get("/api/v1/nodes").path("items")) {
                Map<String, Object> row = KubeObjects.nodeRow(n);
                if (!"Ready".equals(row.get("status")) || row.containsKey("problems")) {
                    nodes.add(Map.of("node", n.path("metadata").path("name").asText(), "status", row.get("status"),
                            "problems", row.getOrDefault("problems", List.of())));
                }
            }
            out.put("nodes", nodes.isEmpty() ? "all Ready" : nodes);
            problems += nodes.size();
        }

        List<Map<String, Object>> pods = new ArrayList<>();
        List<Map<String, Object>> workloads = new ArrayList<>();
        List<Map<String, Object>> pvcs = new ArrayList<>();
        for (String ns : scopes) {
            for (JsonNode p : k.get(path("/api/v1", ns, "pods")).path("items")) {
                Map<String, Object> issue = podIssue(p);
                if (issue != null) {
                    pods.add(issue);
                }
            }
            for (String kind : List.of("deployments", "statefulsets", "daemonsets")) {
                for (JsonNode w : k.get(path("/apis/apps/v1", ns, kind)).path("items")) {
                    Map<String, Object> s = WorkloadTools.rolloutStatus(kindOf(kind), w);
                    if (!"complete".equals(s.get("rollout"))) {
                        workloads.add(Map.of("workload", s.get("workload"), "namespace", s.get("namespace"),
                                "rollout", s.get("rollout"), "replicas", s.get("replicas")));
                    }
                }
            }
            for (JsonNode pvc : k.get(path("/api/v1", ns, "persistentvolumeclaims")).path("items")) {
                if (!"Bound".equals(pvc.path("status").path("phase").asText())) {
                    pvcs.add(KubeObjects.row("PersistentVolumeClaim", pvc));
                }
            }
        }
        out.put("pods", pods.isEmpty() ? "all healthy" : pods);
        out.put("workloads", workloads.isEmpty() ? "all rolled out" : workloads);
        if (!pvcs.isEmpty()) {
            out.put("persistentVolumeClaims", pvcs);
        }
        problems += pods.size() + workloads.size() + pvcs.size();

        Instant since = Instant.now().minus(Duration.ofMinutes(clamp(eventMinutes, 60, 1, 1440)));
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (String ns : scopes) {
            for (JsonNode e : k.get(path("/api/v1", ns, "events") + query("fieldSelector", "type=Warning")).path("items")) {
                Map<String, Object> row = eventRow(e);
                if (!after((String) row.get("time"), since)) {
                    continue;
                }
                String key = row.get("namespace") + "|" + row.get("object") + "|" + row.get("reason");
                Map<String, Object> g = grouped.get(key);
                if (g == null) {
                    grouped.put(key, row);
                } else {
                    g.put("count", (int) g.get("count") + (int) row.get("count"));
                    if (((String) row.get("time")).compareTo((String) g.get("time")) > 0) {
                        g.put("time", row.get("time"));
                        g.put("message", row.get("message"));
                    }
                }
            }
        }
        List<Map<String, Object>> warnings = new ArrayList<>(grouped.values());
        warnings.sort((a, b) -> ((String) b.get("time")).compareTo((String) a.get("time")));
        out.put("warningEvents", warnings.size() > 30 ? warnings.subList(0, 30) : warnings);
        out.put("healthy", problems == 0);
        out.put("problemCount", problems);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static String path(String base, String ns, String plural) {
        return ns == null ? base + "/" + plural : base + "/namespaces/" + HttpSupport.enc(ns) + "/" + plural;
    }

    private static String kindOf(String plural) {
        return switch (plural) {
            case "deployments" -> "Deployment";
            case "statefulsets" -> "StatefulSet";
            default -> "DaemonSet";
        };
    }

    /** Null when the pod looks fine; otherwise what is wrong and what to do next. */
    static Map<String, Object> podIssue(JsonNode p) {
        String phase = p.path("status").path("phase").asText();
        if (phase.equals("Succeeded")) {
            return null;
        }
        String status = KubeObjects.podStatus(p);
        int[] rr = KubeObjects.readyRestarts(p.path("status").path("containerStatuses"));
        boolean notReady = rr[0] < rr[1];
        boolean young = Duration.between(parse(p.path("status").path("startTime").asText("")), Instant.now()).toSeconds() < 120;
        boolean restarting = rr[2] >= 3;
        boolean bad = !phase.equals("Running") || (notReady && !young) || restarting || !status.equals("Running");
        if (!bad || status.equals("Completed")) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("namespace", p.path("metadata").path("namespace").asText());
        m.put("pod", p.path("metadata").path("name").asText());
        m.put("status", status);
        m.put("ready", rr[0] + "/" + rr[1]);
        m.put("restarts", rr[2]);
        List<String> details = new ArrayList<>();
        for (JsonNode cs : p.path("status").path("containerStatuses")) {
            JsonNode w = cs.path("state").path("waiting");
            if (w.has("message")) {
                details.add(cs.path("name").asText() + ": " + HttpSupport.truncate(w.path("message").asText(), 300));
            }
            JsonNode last = cs.path("lastState").path("terminated");
            if (!last.isMissingNode()) {
                details.add(cs.path("name").asText() + " last exit: " + last.path("reason").asText() + " (code "
                        + last.path("exitCode").asInt() + ") at " + last.path("finishedAt").asText());
                if ("OOMKilled".equals(last.path("reason").asText())) {
                    status = "OOMKilled";
                }
            }
        }
        if (p.path("status").has("message")) {
            details.add(HttpSupport.truncate(p.path("status").path("message").asText(), 300));
        }
        if (!details.isEmpty()) {
            m.put("details", details);
        }
        String key = status.startsWith("Init:") ? status.substring(5) : status;
        String hint = HINTS.get(key);
        if (hint == null && restarting) {
            hint = HINTS.get("CrashLoopBackOff");
        }
        if (hint == null && notReady) {
            hint = "containers run but are not Ready: check the readiness probe and k8s_logs";
        }
        if (hint != null) {
            m.put("hint", hint);
        }
        return m;
    }

    static Map<String, Object> eventRow(JsonNode e) {
        Map<String, Object> m = new LinkedHashMap<>();
        String time = e.path("lastTimestamp").asText("");
        if (time.isEmpty() || time.equals("null")) {
            time = e.path("eventTime").asText("");
        }
        if (time.isEmpty() || time.equals("null")) {
            time = e.path("metadata").path("creationTimestamp").asText("");
        }
        m.put("time", time);
        m.put("namespace", e.path("metadata").path("namespace").asText());
        m.put("type", e.path("type").asText());
        m.put("reason", e.path("reason").asText());
        m.put("object", e.path("involvedObject").path("kind").asText() + "/" + e.path("involvedObject").path("name").asText());
        m.put("message", HttpSupport.truncate(e.path("message").asText(), 500));
        m.put("count", e.path("count").asInt(1));
        return m;
    }

    static boolean after(String time, Instant since) {
        return parse(time).isAfter(since);
    }

    static Instant parse(String time) {
        try {
            return Instant.parse(time);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
