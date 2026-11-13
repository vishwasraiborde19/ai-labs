package com.ailabs.kube.k8s;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ailabs.kube.http.HttpSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/** Turns API objects into what the model sees: one-line rows, cleaned YAML, masked Secrets. */
public final class KubeObjects {

    public static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .build();

    private static final String LAST_APPLIED = "kubectl.kubernetes.io/last-applied-configuration";

    private KubeObjects() {}

    // ------------------------------------------------------------------ cleaning

    /**
     * A copy without managedFields and the last-applied annotation (noise, and the latter can hold
     * Secret data). Secret values are replaced by their size: the agent never returns them.
     */
    public static ObjectNode clean(JsonNode obj, String kind) {
        ObjectNode o = obj.deepCopy();
        if (o.path("metadata") instanceof ObjectNode meta) {
            meta.remove("managedFields");
            if (meta.path("annotations") instanceof ObjectNode ann) {
                ann.remove(LAST_APPLIED);
                if (ann.isEmpty()) {
                    meta.remove("annotations");
                }
            }
        }
        if ("Secret".equals(kind) || "Secret".equals(o.path("kind").asText())) {
            maskSecret(o);
        }
        return o;
    }

    static void maskSecret(ObjectNode o) {
        for (String field : List.of("data", "stringData")) {
            if (o.path(field) instanceof ObjectNode d) {
                Iterator<Map.Entry<String, JsonNode>> it = d.fields();
                List<String> keys = new ArrayList<>();
                while (it.hasNext()) {
                    keys.add(it.next().getKey());
                }
                for (String k : keys) {
                    String v = d.path(k).asText("");
                    int size = field.equals("data") ? decodedSize(v) : v.length();
                    d.put(k, "<hidden: " + size + " bytes>");
                }
            }
        }
    }

    private static int decodedSize(String b64) {
        try {
            return Base64.getDecoder().decode(b64).length;
        } catch (IllegalArgumentException e) {
            return b64.length();
        }
    }

    public static String yaml(JsonNode n) {
        try {
            return YAML.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ rows

    /** One line per object, like the columns of {@code kubectl get}. */
    public static Map<String, Object> row(String kind, JsonNode o) {
        JsonNode meta = o.path("metadata");
        JsonNode spec = o.path("spec");
        JsonNode st = o.path("status");
        Map<String, Object> m = new LinkedHashMap<>();
        if (!meta.path("namespace").asText().isEmpty()) {
            m.put("namespace", meta.path("namespace").asText());
        }
        m.put("name", meta.path("name").asText());
        switch (kind) {
            case "Pod" -> {
                m.put("status", podStatus(o));
                int[] rr = readyRestarts(st.path("containerStatuses"));
                m.put("ready", rr[0] + "/" + rr[1]);
                m.put("restarts", rr[2]);
                m.put("node", spec.path("nodeName").asText(""));
                m.put("ip", st.path("podIP").asText(""));
            }
            case "Deployment", "StatefulSet", "ReplicaSet" -> {
                m.put("ready", st.path("readyReplicas").asInt(0) + "/" + spec.path("replicas").asInt(1));
                m.put("upToDate", st.path("updatedReplicas").asInt(0));
                m.put("available", st.path("availableReplicas").asInt(0));
                m.put("images", images(spec.path("template").path("spec")));
            }
            case "DaemonSet" -> {
                m.put("desired", st.path("desiredNumberScheduled").asInt(0));
                m.put("ready", st.path("numberReady").asInt(0));
                m.put("upToDate", st.path("updatedNumberScheduled").asInt(0));
                m.put("available", st.path("numberAvailable").asInt(0));
                m.put("images", images(spec.path("template").path("spec")));
            }
            case "Service" -> {
                m.put("type", spec.path("type").asText("ClusterIP"));
                m.put("clusterIP", spec.path("clusterIP").asText(""));
                List<String> ext = new ArrayList<>();
                st.path("loadBalancer").path("ingress").forEach(i -> ext.add(i.path("ip").asText(i.path("hostname").asText())));
                spec.path("externalIPs").forEach(i -> ext.add(i.asText()));
                if (!ext.isEmpty()) {
                    m.put("external", ext);
                }
                List<String> ports = new ArrayList<>();
                spec.path("ports").forEach(p -> ports.add(p.path("port").asInt() + (p.has("nodePort") ? ":" + p.path("nodePort").asInt() : "")
                        + "/" + p.path("protocol").asText("TCP") + (p.has("targetPort") ? "->" + p.path("targetPort").asText() : "")));
                m.put("ports", ports);
                if (spec.has("selector")) {
                    m.put("selector", selectorString(spec.path("selector")));
                }
            }
            case "Ingress" -> {
                m.put("class", spec.path("ingressClassName").asText(""));
                List<String> hosts = new ArrayList<>();
                spec.path("rules").forEach(r -> hosts.add(r.path("host").asText("*")));
                m.put("hosts", hosts);
                List<String> addr = new ArrayList<>();
                st.path("loadBalancer").path("ingress").forEach(i -> addr.add(i.path("ip").asText(i.path("hostname").asText())));
                m.put("address", addr);
            }
            case "Node" -> m.putAll(nodeRow(o));
            case "Namespace", "PersistentVolume" -> {
                m.put("status", st.path("phase").asText());
                if (kind.equals("PersistentVolume")) {
                    m.put("capacity", spec.path("capacity").path("storage").asText(""));
                    m.put("claim", spec.path("claimRef").path("namespace").asText("") + "/" + spec.path("claimRef").path("name").asText(""));
                    m.put("storageClass", spec.path("storageClassName").asText(""));
                    m.put("reclaimPolicy", spec.path("persistentVolumeReclaimPolicy").asText(""));
                }
            }
            case "PersistentVolumeClaim" -> {
                m.put("status", st.path("phase").asText());
                m.put("volume", spec.path("volumeName").asText(""));
                m.put("capacity", st.path("capacity").path("storage").asText(spec.path("resources").path("requests").path("storage").asText("")));
                m.put("storageClass", spec.path("storageClassName").asText(""));
            }
            case "Job" -> {
                m.put("completions", st.path("succeeded").asInt(0) + "/" + spec.path("completions").asInt(1));
                m.put("active", st.path("active").asInt(0));
                m.put("failed", st.path("failed").asInt(0));
            }
            case "CronJob" -> {
                m.put("schedule", spec.path("schedule").asText());
                m.put("suspend", spec.path("suspend").asBoolean(false));
                m.put("active", st.path("active").size());
                m.put("lastSchedule", st.path("lastScheduleTime").asText(""));
            }
            case "Secret" -> {
                m.put("type", o.path("type").asText());
                m.put("keys", fieldNames(o.path("data")));
            }
            case "ConfigMap" -> m.put("keys", fieldNames(o.path("data")));
            case "HorizontalPodAutoscaler" -> {
                m.put("target", spec.path("scaleTargetRef").path("kind").asText() + "/" + spec.path("scaleTargetRef").path("name").asText());
                m.put("replicas", st.path("currentReplicas").asInt(0) + " (min " + spec.path("minReplicas").asInt(1)
                        + ", max " + spec.path("maxReplicas").asInt() + ")");
            }
            case "Event" -> {
                m.put("type", o.path("type").asText());
                m.put("reason", o.path("reason").asText());
                m.put("object", o.path("involvedObject").path("kind").asText() + "/" + o.path("involvedObject").path("name").asText());
                m.put("message", o.path("message").asText());
            }
            default -> {
                String ready = conditionSummary(st.path("conditions"));
                if (!ready.isEmpty()) {
                    m.put("conditions", ready);
                }
                if (st.has("phase")) {
                    m.put("phase", st.path("phase").asText());
                }
            }
        }
        m.put("age", age(meta.path("creationTimestamp").asText("")));
        return m;
    }

    /** The STATUS column of {@code kubectl get pods}. */
    public static String podStatus(JsonNode p) {
        JsonNode st = p.path("status");
        String reason = st.path("reason").asText(st.path("phase").asText("Unknown"));
        boolean initializing = false;
        int i = 0;
        for (JsonNode cs : st.path("initContainerStatuses")) {
            JsonNode term = cs.path("state").path("terminated");
            if (!term.isMissingNode() && term.path("exitCode").asInt() == 0) {
                i++;
                continue;
            }
            String r = !term.isMissingNode() ? term.path("reason").asText("Error")
                    : cs.path("state").path("waiting").path("reason").asText("");
            reason = "Init:" + (r.isEmpty() || r.equals("PodInitializing") ? i + "/" + st.path("initContainerStatuses").size() : r);
            initializing = true;
            break;
        }
        if (!initializing) {
            for (JsonNode cs : st.path("containerStatuses")) {
                JsonNode w = cs.path("state").path("waiting");
                JsonNode t = cs.path("state").path("terminated");
                if (!w.path("reason").asText("").isEmpty()) {
                    reason = w.path("reason").asText();
                } else if (!t.isMissingNode()) {
                    reason = t.path("reason").asText(t.path("signal").asInt(0) != 0 ? "Signal:" + t.path("signal").asInt()
                            : "ExitCode:" + t.path("exitCode").asInt());
                }
            }
        }
        if (p.path("metadata").hasNonNull("deletionTimestamp")) {
            reason = "Terminating";
        }
        return reason;
    }

    /** {ready, total, restarts} over container statuses. */
    public static int[] readyRestarts(JsonNode statuses) {
        int ready = 0;
        int total = 0;
        int restarts = 0;
        for (JsonNode cs : statuses) {
            total++;
            if (cs.path("ready").asBoolean()) {
                ready++;
            }
            restarts += cs.path("restartCount").asInt();
        }
        return new int[] {ready, total, restarts};
    }

    public static Map<String, Object> nodeRow(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        String ready = "Unknown";
        List<String> pressure = new ArrayList<>();
        for (JsonNode c : n.path("status").path("conditions")) {
            String type = c.path("type").asText();
            if (type.equals("Ready")) {
                ready = c.path("status").asText().equals("True") ? "Ready" : "NotReady";
            } else if (c.path("status").asText().equals("True")) {
                pressure.add(type);
            }
        }
        if (n.path("spec").path("unschedulable").asBoolean(false)) {
            ready += ",SchedulingDisabled";
        }
        m.put("status", ready);
        if (!pressure.isEmpty()) {
            m.put("problems", pressure);
        }
        List<String> roles = new ArrayList<>();
        n.path("metadata").path("labels").fieldNames().forEachRemaining(l -> {
            if (l.startsWith("node-role.kubernetes.io/")) {
                roles.add(l.substring("node-role.kubernetes.io/".length()));
            }
        });
        m.put("roles", roles);
        JsonNode info = n.path("status").path("nodeInfo");
        m.put("version", info.path("kubeletVersion").asText());
        n.path("status").path("addresses").forEach(a -> {
            if (a.path("type").asText().equals("InternalIP")) {
                m.put("internalIP", a.path("address").asText());
            }
        });
        m.put("os", info.path("osImage").asText());
        m.put("runtime", info.path("containerRuntimeVersion").asText());
        JsonNode alloc = n.path("status").path("allocatable");
        m.put("allocatable", Map.of("cpu", alloc.path("cpu").asText(), "memory", alloc.path("memory").asText(),
                "pods", alloc.path("pods").asText()));
        List<String> taints = new ArrayList<>();
        n.path("spec").path("taints").forEach(t -> taints.add(t.path("key").asText()
                + (t.hasNonNull("value") ? "=" + t.path("value").asText() : "") + ":" + t.path("effect").asText()));
        if (!taints.isEmpty()) {
            m.put("taints", taints);
        }
        return m;
    }

    public static Map<String, String> images(JsonNode podSpec) {
        Map<String, String> m = new LinkedHashMap<>();
        podSpec.path("initContainers").forEach(c -> m.put("init:" + c.path("name").asText(), c.path("image").asText()));
        podSpec.path("containers").forEach(c -> m.put(c.path("name").asText(), c.path("image").asText()));
        return m;
    }

    public static List<String> conditions(JsonNode conditions) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : conditions) {
            String reason = c.path("reason").asText("");
            String msg = c.path("message").asText("");
            out.add(c.path("type").asText() + "=" + c.path("status").asText()
                    + (reason.isEmpty() ? "" : " " + reason) + (msg.isEmpty() ? "" : ": " + HttpSupport.truncate(msg, 300)));
        }
        return out;
    }

    static String conditionSummary(JsonNode conditions) {
        List<String> out = new ArrayList<>();
        conditions.forEach(c -> out.add(c.path("type").asText() + "=" + c.path("status").asText()));
        return String.join(",", out);
    }

    /** matchLabels as {@code a=b,c=d}; for a Service the selector map itself. */
    public static String selectorString(JsonNode labels) {
        List<String> parts = new ArrayList<>();
        labels.fields().forEachRemaining(e -> parts.add(e.getKey() + "=" + e.getValue().asText()));
        return String.join(",", parts);
    }

    /** Label selector of a workload (Deployment, StatefulSet, DaemonSet, ReplicaSet, Job). */
    public static String workloadSelector(JsonNode workload) {
        JsonNode sel = workload.path("spec").path("selector");
        List<String> parts = new ArrayList<>();
        sel.path("matchLabels").fields().forEachRemaining(e -> parts.add(e.getKey() + "=" + e.getValue().asText()));
        for (JsonNode ex : sel.path("matchExpressions")) {
            List<String> vals = new ArrayList<>();
            ex.path("values").forEach(v -> vals.add(v.asText()));
            String key = ex.path("key").asText();
            switch (ex.path("operator").asText()) {
                case "In" -> parts.add(key + " in (" + String.join(",", vals) + ")");
                case "NotIn" -> parts.add(key + " notin (" + String.join(",", vals) + ")");
                case "Exists" -> parts.add(key);
                case "DoesNotExist" -> parts.add("!" + key);
                default -> { }
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException(workload.path("metadata").path("name").asText() + " has no label selector");
        }
        return String.join(",", parts);
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    public static String age(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return "";
        }
        try {
            return human(Duration.between(Instant.parse(timestamp), Instant.now()));
        } catch (RuntimeException e) {
            return timestamp;
        }
    }

    static String human(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 120) {
            return s + "s";
        }
        if (s < 7200) {
            return s / 60 + "m";
        }
        if (s < 172800) {
            return s / 3600 + "h" + (s % 3600) / 60 + "m";
        }
        return s / 86400 + "d" + (s % 86400) / 3600 + "h";
    }

    // ------------------------------------------------------------------ quantities

    /** CPU quantity in millicores: {@code 250m}, {@code 2}, {@code 123456789n}. */
    public static double cpuMilli(String q) {
        if (q == null || q.isEmpty()) {
            return 0;
        }
        char last = q.charAt(q.length() - 1);
        String num = q.substring(0, q.length() - 1);
        return switch (last) {
            case 'n' -> Double.parseDouble(num) / 1_000_000;
            case 'u' -> Double.parseDouble(num) / 1_000;
            case 'm' -> Double.parseDouble(num);
            default -> Double.parseDouble(q) * 1000;
        };
    }

    /** Memory quantity in MiB: {@code 128Mi}, {@code 1Gi}, {@code 2048Ki}, {@code 1G}, plain bytes. */
    public static double memMi(String q) {
        if (q == null || q.isEmpty()) {
            return 0;
        }
        String[][] units = {{"Ki", "1024"}, {"Mi", "1048576"}, {"Gi", "1073741824"}, {"Ti", "1099511627776"},
                {"k", "1000"}, {"K", "1000"}, {"M", "1000000"}, {"G", "1000000000"}, {"T", "1000000000000"}};
        for (String[] u : units) {
            if (q.endsWith(u[0])) {
                return Double.parseDouble(q.substring(0, q.length() - u[0].length())) * Double.parseDouble(u[1]) / 1048576;
            }
        }
        return Double.parseDouble(q) / 1048576;
    }
}
