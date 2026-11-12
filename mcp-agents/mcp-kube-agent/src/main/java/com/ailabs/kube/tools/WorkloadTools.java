package com.ailabs.kube.tools;

import static com.ailabs.kube.tools.Params.CLUSTER;
import static com.ailabs.kube.tools.Params.NAMESPACE;
import static com.ailabs.kube.tools.Params.blank;
import static com.ailabs.kube.tools.Params.clamp;
import static com.ailabs.kube.tools.Params.query;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.kube.config.KubeProperties.Cluster;
import com.ailabs.kube.http.HttpSupport;
import com.ailabs.kube.k8s.ApiResource;
import com.ailabs.kube.k8s.Clusters;
import com.ailabs.kube.k8s.KubeClient;
import com.ailabs.kube.k8s.KubeObjects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Skill: run workloads: scale, restart, change images, follow rollouts and roll back. */
@Component
public class WorkloadTools {

    private static final String REVISION = "deployment.kubernetes.io/revision";
    private static final Set<String> SCALABLE = Set.of("Deployment", "StatefulSet", "ReplicaSet");
    private static final Set<String> ROLLABLE = Set.of("Deployment", "StatefulSet", "DaemonSet");
    private static final String WK = "deployment (default), statefulset or daemonset";

    private final Clusters clusters;

    public WorkloadTools(Clusters clusters) {
        this.clusters = clusters;
    }

    @Tool(name = "k8s_scale", description = "Set the replica count of a deployment, statefulset or replicaset.")
    public Map<String, Object> scale(@ToolParam(required = false, description = CLUSTER) String cluster,
                                     @ToolParam(description = NAMESPACE) String namespace,
                                     @ToolParam(description = "Workload name") String name,
                                     @ToolParam(description = "Replicas, 0..100") Integer replicas,
                                     @ToolParam(required = false, description = "deployment (default), statefulset or replicaset") String kind) {
        if (replicas == null || replicas < 0 || replicas > 100) {
            throw new IllegalArgumentException("replicas must be 0..100");
        }
        Target t = target(cluster, kind, namespace, name, SCALABLE, true);
        JsonNode before = t.k().get(t.r().itemPath(namespace, name) + "/scale");
        t.k().patch(t.r().itemPath(namespace, name) + "/scale", "application/merge-patch+json",
                "{\"spec\":{\"replicas\":" + replicas + "}}");
        return Map.of("workload", t.label(), "from", before.path("spec").path("replicas").asInt(), "to", replicas);
    }

    @Tool(name = "k8s_rollout_restart", description = "Rolling restart of a deployment, statefulset or daemonset (kubectl rollout restart).")
    public Map<String, Object> restart(@ToolParam(required = false, description = CLUSTER) String cluster,
                                       @ToolParam(description = NAMESPACE) String namespace,
                                       @ToolParam(description = "Workload name") String name,
                                       @ToolParam(required = false, description = WK) String kind) {
        Target t = target(cluster, kind, namespace, name, ROLLABLE, true);
        ObjectNode patch = HttpSupport.JSON.createObjectNode();
        patch.putObject("spec").putObject("template").putObject("metadata").putObject("annotations")
                .put("kubectl.kubernetes.io/restartedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        t.k().patch(t.r().itemPath(namespace, name) + query("fieldManager", ResourceTools.FIELD_MANAGER),
                "application/strategic-merge-patch+json", patch.toString());
        return Map.of("workload", t.label(), "state", "restart triggered; follow with k8s_rollout_status");
    }

    @Tool(name = "k8s_rollout_status", description = "Rollout state of a deployment, statefulset or daemonset: complete, "
            + "progressing or failed, with replica counts. With waitSeconds, waits like kubectl rollout status.")
    public Map<String, Object> status(@ToolParam(required = false, description = CLUSTER) String cluster,
                                      @ToolParam(description = NAMESPACE) String namespace,
                                      @ToolParam(description = "Workload name") String name,
                                      @ToolParam(required = false, description = WK) String kind,
                                      @ToolParam(required = false, description = "Seconds to wait for completion, default 0, max 900") Integer waitSeconds) {
        Target t = target(cluster, kind, namespace, name, ROLLABLE, false);
        return await(t, namespace, name, Duration.ofSeconds(clamp(waitSeconds, 0, 0, 900)));
    }

    @Tool(name = "k8s_set_image", description = "Change the image of one container in a deployment, statefulset or "
            + "daemonset (kubectl set image), then optionally wait for the rollout.")
    public Map<String, Object> setImage(@ToolParam(required = false, description = CLUSTER) String cluster,
                                        @ToolParam(description = NAMESPACE) String namespace,
                                        @ToolParam(description = "Workload name") String name,
                                        @ToolParam(description = "Full image reference, e.g. nginx:1.27.3") String image,
                                        @ToolParam(required = false, description = "Container name; optional when there is only one") String container,
                                        @ToolParam(required = false, description = WK) String kind,
                                        @ToolParam(required = false, description = "Seconds to wait for the rollout, default 120, max 900") Integer waitSeconds) {
        Target t = target(cluster, kind, namespace, name, ROLLABLE, true);
        if (blank(image)) {
            throw new IllegalArgumentException("image is required");
        }
        JsonNode w = t.k().get(t.r().itemPath(namespace, name));
        JsonNode podSpec = w.path("spec").path("template").path("spec");
        List<String> names = new ArrayList<>();
        List<String> inits = new ArrayList<>();
        podSpec.path("containers").forEach(c -> names.add(c.path("name").asText()));
        podSpec.path("initContainers").forEach(c -> inits.add(c.path("name").asText()));
        String target = blank(container) ? (names.size() == 1 ? names.get(0) : null) : container;
        if (target == null || !(names.contains(target) || inits.contains(target))) {
            throw new IllegalArgumentException(t.label() + " has containers " + names
                    + (inits.isEmpty() ? "" : " and init containers " + inits) + "; pass one as container");
        }
        String list = names.contains(target) ? "containers" : "initContainers";
        String previous = "";
        for (JsonNode c : podSpec.path(list)) {
            if (c.path("name").asText().equals(target)) {
                previous = c.path("image").asText();
            }
        }
        ObjectNode patch = HttpSupport.JSON.createObjectNode();
        patch.putObject("spec").putObject("template").putObject("spec").putArray(list)
                .addObject().put("name", target).put("image", image);
        t.k().patch(t.r().itemPath(namespace, name) + query("fieldManager", ResourceTools.FIELD_MANAGER),
                "application/strategic-merge-patch+json", patch.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workload", t.label());
        out.put("container", target);
        out.put("previousImage", previous);
        out.put("image", image);
        int wait = clamp(waitSeconds, 120, 0, 900);
        if (wait > 0) {
            out.put("status", await(t, namespace, name, Duration.ofSeconds(wait)));
        }
        return out;
    }

    @Tool(name = "k8s_rollout_history", description = "Revisions of a deployment with their images and change-cause, newest first.")
    public List<Map<String, Object>> history(@ToolParam(required = false, description = CLUSTER) String cluster,
                                             @ToolParam(description = NAMESPACE) String namespace,
                                             @ToolParam(description = "Deployment name") String name) {
        Target t = target(cluster, "deployment", namespace, name, Set.of("Deployment"), false);
        JsonNode d = t.k().get(t.r().itemPath(namespace, name));
        int current = d.path("metadata").path("annotations").path(REVISION).asInt(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode rs : ownedReplicaSets(t.k(), namespace, d)) {
            Map<String, Object> m = new LinkedHashMap<>();
            int rev = revision(rs);
            m.put("revision", rev);
            m.put("current", rev == current);
            m.put("replicaSet", rs.path("metadata").path("name").asText());
            m.put("replicas", rs.path("status").path("replicas").asInt(0));
            m.put("images", KubeObjects.images(rs.path("spec").path("template").path("spec")));
            String cause = rs.path("metadata").path("annotations").path("kubernetes.io/change-cause").asText("");
            if (!cause.isEmpty()) {
                m.put("changeCause", cause);
            }
            m.put("created", rs.path("metadata").path("creationTimestamp").asText());
            out.add(m);
        }
        return out;
    }

    @Tool(name = "k8s_rollout_undo", description = "Roll a deployment back to the previous revision, or to toRevision "
            + "(kubectl rollout undo). See k8s_rollout_history for revisions.")
    public Map<String, Object> undo(@ToolParam(required = false, description = CLUSTER) String cluster,
                                    @ToolParam(description = NAMESPACE) String namespace,
                                    @ToolParam(description = "Deployment name") String name,
                                    @ToolParam(required = false, description = "Revision to return to; omit for the previous one") Integer toRevision,
                                    @ToolParam(required = false, description = "Seconds to wait for the rollout, default 120, max 900") Integer waitSeconds) {
        Target t = target(cluster, "deployment", namespace, name, Set.of("Deployment"), true);
        JsonNode d = t.k().get(t.r().itemPath(namespace, name));
        int current = d.path("metadata").path("annotations").path(REVISION).asInt(0);
        int want = toRevision == null ? 0 : toRevision;
        List<JsonNode> owned = ownedReplicaSets(t.k(), namespace, d);
        JsonNode target = owned.stream()
                .filter(rs -> want > 0 ? revision(rs) == want : revision(rs) < current)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No revision " + (want > 0 ? want : "before " + current)
                        + " for " + name + ". Available: " + owned.stream().map(rs -> String.valueOf(revision(rs))).collect(Collectors.joining(", "))));
        if (revision(target) == current) {
            throw new IllegalStateException("Revision " + current + " is already current");
        }
        ObjectNode template = target.path("spec").path("template").deepCopy();
        if (template.path("metadata").path("labels") instanceof ObjectNode labels) {
            labels.remove("pod-template-hash");
        }
        ArrayNode ops = HttpSupport.JSON.createArrayNode();
        ops.addObject().put("op", "replace").put("path", "/spec/template").set("value", template);
        t.k().patch(t.r().itemPath(namespace, name) + query("fieldManager", ResourceTools.FIELD_MANAGER),
                "application/json-patch+json", ops.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deployment", name);
        out.put("fromRevision", current);
        out.put("toRevision", revision(target));
        out.put("images", KubeObjects.images(template.path("spec")));
        int wait = clamp(waitSeconds, 120, 0, 900);
        if (wait > 0) {
            out.put("status", await(t, namespace, name, Duration.ofSeconds(wait)));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    record Target(KubeClient k, ApiResource r, String label) {}

    private Target target(String cluster, String kind, String ns, String name, Set<String> allowed, boolean write) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(blank(kind) ? "deployment" : kind, null);
        if (!allowed.contains(r.kind()) || !r.group().equals("apps")) {
            throw new IllegalArgumentException("kind must be one of " + allowed.stream().map(String::toLowerCase).sorted().toList());
        }
        if (blank(ns) || blank(name)) {
            throw new IllegalArgumentException("namespace and name are required");
        }
        if (write) {
            clusters.checkWrite(c, r, ns, name);
        } else {
            clusters.checkRead(c, r, ns);
        }
        return new Target(k, r, r.kind().toLowerCase() + "/" + name);
    }

    private Map<String, Object> await(Target t, String ns, String name, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Map<String, Object> s = rolloutStatus(t.r().kind(), t.k().get(t.r().itemPath(ns, name)));
            if (!"progressing".equals(s.get("rollout")) || Instant.now().isAfter(deadline)) {
                if ("progressing".equals(s.get("rollout")) && !timeout.isZero()) {
                    s.put("rollout", "progressing (still not done after " + timeout.getSeconds() + "s)");
                }
                return s;
            }
            Params.sleep(3000);
        }
    }

    static Map<String, Object> rolloutStatus(String kind, JsonNode o) {
        JsonNode meta = o.path("metadata");
        JsonNode spec = o.path("spec");
        JsonNode st = o.path("status");
        boolean observed = st.path("observedGeneration").asLong(0) >= meta.path("generation").asLong(0);
        Map<String, Object> counts = new LinkedHashMap<>();
        String rollout = "progressing";
        switch (kind) {
            case "Deployment" -> {
                int desired = spec.path("replicas").asInt(1);
                int updated = st.path("updatedReplicas").asInt(0);
                int available = st.path("availableReplicas").asInt(0);
                counts.put("desired", desired);
                counts.put("updated", updated);
                counts.put("ready", st.path("readyReplicas").asInt(0));
                counts.put("available", available);
                counts.put("unavailable", st.path("unavailableReplicas").asInt(0));
                for (JsonNode c : st.path("conditions")) {
                    if ("Progressing".equals(c.path("type").asText()) && "ProgressDeadlineExceeded".equals(c.path("reason").asText())) {
                        rollout = "failed";
                    }
                }
                if (!"failed".equals(rollout) && observed && updated == desired && available == desired
                        && st.path("replicas").asInt(0) == desired) {
                    rollout = "complete";
                }
                counts.put("revision", meta.path("annotations").path(REVISION).asText("?"));
            }
            case "StatefulSet" -> {
                int desired = spec.path("replicas").asInt(1);
                int updated = st.path("updatedReplicas").asInt(0);
                int ready = st.path("readyReplicas").asInt(0);
                counts.put("desired", desired);
                counts.put("updated", updated);
                counts.put("ready", ready);
                boolean onDelete = "OnDelete".equals(spec.path("updateStrategy").path("type").asText());
                boolean revisionDone = st.path("updateRevision").asText("").equals(st.path("currentRevision").asText(""));
                if (observed && ready == desired && (onDelete || (updated == desired && revisionDone)
                        || spec.path("updateStrategy").path("rollingUpdate").path("partition").asInt(0) > 0)) {
                    rollout = "complete";
                }
            }
            case "DaemonSet" -> {
                int desired = st.path("desiredNumberScheduled").asInt(0);
                int updated = st.path("updatedNumberScheduled").asInt(0);
                int available = st.path("numberAvailable").asInt(0);
                counts.put("desired", desired);
                counts.put("updated", updated);
                counts.put("ready", st.path("numberReady").asInt(0));
                counts.put("available", available);
                if (observed && updated == desired && available == desired) {
                    rollout = "complete";
                }
            }
            default -> throw new IllegalArgumentException("No rollout for " + kind);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workload", kind.toLowerCase() + "/" + meta.path("name").asText());
        m.put("namespace", meta.path("namespace").asText());
        m.put("rollout", rollout);
        m.put("replicas", counts);
        m.put("images", KubeObjects.images(spec.path("template").path("spec")));
        List<String> conditions = KubeObjects.conditions(st.path("conditions"));
        if (!conditions.isEmpty()) {
            m.put("conditions", conditions);
        }
        return m;
    }

    static List<JsonNode> ownedReplicaSets(KubeClient k, String ns, JsonNode deployment) {
        String uid = deployment.path("metadata").path("uid").asText();
        List<JsonNode> owned = new ArrayList<>();
        for (JsonNode rs : k.get("/apis/apps/v1/namespaces/" + HttpSupport.enc(ns) + "/replicasets"
                + query("labelSelector", KubeObjects.workloadSelector(deployment))).path("items")) {
            for (JsonNode o : rs.path("metadata").path("ownerReferences")) {
                if (uid.equals(o.path("uid").asText())) {
                    owned.add(rs);
                }
            }
        }
        owned.sort(Comparator.comparingInt(WorkloadTools::revision).reversed());
        return owned;
    }

    static int revision(JsonNode rs) {
        return rs.path("metadata").path("annotations").path(REVISION).asInt(0);
    }
}
