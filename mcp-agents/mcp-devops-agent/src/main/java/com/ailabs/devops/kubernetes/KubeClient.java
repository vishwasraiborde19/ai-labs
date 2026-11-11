package com.ailabs.devops.kubernetes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.ailabs.devops.config.DevopsProperties.KubeCluster;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Kubernetes API over HTTPS with a bearer (service account) token. Covers what a deploy needs:
 * read deployments and pods, set an image, restart, scale, roll back, logs and events.
 */
public class KubeClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String REVISION = "deployment.kubernetes.io/revision";

    private final KubeCluster cfg;
    private final Supplier<String> token;
    private final HttpClient http;

    public KubeClient(KubeCluster cfg, Supplier<String> token, String caPem) {
        this.cfg = cfg;
        this.token = token;
        this.http = HttpSupport.client(cfg.insecureSkipTlsVerify(), caPem, false, Duration.ofSeconds(10));
    }

    public String name() {
        return cfg.name();
    }

    public JsonNode version() {
        return getJson("/version");
    }

    // ------------------------------------------------------------------ deployments

    public JsonNode deploymentRaw(String ns, String name) {
        return getJson(deployPath(ns, name));
    }

    public Map<String, Object> deploymentStatus(String ns, String name) {
        return summarize(deploymentRaw(ns, name));
    }

    public List<Map<String, Object>> listDeployments(String ns) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode d : getJson("/apis/apps/v1/namespaces/" + seg(ns) + "/deployments").path("items")) {
            out.add(summarize(d));
        }
        return out;
    }

    /** Poll until the rollout completes, fails its progress deadline, or the timeout passes. */
    public Map<String, Object> awaitRollout(String ns, String name, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            JsonNode d = deploymentRaw(ns, name);
            Map<String, Object> s = summarize(d);
            String state = (String) s.get("rollout");
            if (!"progressing".equals(state) || Instant.now().isAfter(deadline)) {
                if ("progressing".equals(state)) {
                    s.put("rollout", "timeout");
                }
                return s;
            }
            sleep(3000);
        }
    }

    public Map<String, Object> setImage(String ns, String name, String container, String image) {
        JsonNode d = deploymentRaw(ns, name);
        List<String> names = new ArrayList<>();
        d.path("spec").path("template").path("spec").path("containers").forEach(c -> names.add(c.path("name").asText()));
        String target = container == null || container.isBlank() ? (names.size() == 1 ? names.get(0) : null) : container;
        if (target == null || !names.contains(target)) {
            throw new IllegalArgumentException("Deployment " + name + " has containers " + names
                    + "; pass one of them as container");
        }
        ObjectNode patch = HttpSupport.JSON.createObjectNode();
        patch.putObject("spec").putObject("template").putObject("spec").putArray("containers")
                .addObject().put("name", target).put("image", image);
        patch(deployPath(ns, name), "application/strategic-merge-patch+json", patch.toString());
        return Map.of("deployment", name, "container", target, "image", image, "previousRevision",
                d.path("metadata").path("annotations").path(REVISION).asText("?"));
    }

    public void restart(String ns, String name) {
        ObjectNode patch = HttpSupport.JSON.createObjectNode();
        patch.putObject("spec").putObject("template").putObject("metadata").putObject("annotations")
                .put("kubectl.kubernetes.io/restartedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        patch(deployPath(ns, name), "application/strategic-merge-patch+json", patch.toString());
    }

    public void scale(String ns, String name, int replicas) {
        patch(deployPath(ns, name) + "/scale", "application/merge-patch+json",
                "{\"spec\":{\"replicas\":" + replicas + "}}");
    }

    /**
     * What {@code kubectl rollout undo} does: copy the pod template of an older ReplicaSet back into
     * the deployment. {@code toRevision <= 0} means the revision before the current one.
     */
    public Map<String, Object> rollback(String ns, String name, int toRevision) {
        JsonNode d = deploymentRaw(ns, name);
        String uid = d.path("metadata").path("uid").asText();
        int current = d.path("metadata").path("annotations").path(REVISION).asInt(0);

        List<JsonNode> owned = new ArrayList<>();
        for (JsonNode rs : getJson("/apis/apps/v1/namespaces/" + seg(ns) + "/replicasets?labelSelector="
                + HttpSupport.enc(selector(d))).path("items")) {
            for (JsonNode o : rs.path("metadata").path("ownerReferences")) {
                if (uid.equals(o.path("uid").asText())) {
                    owned.add(rs);
                }
            }
        }
        owned.sort(Comparator.comparingInt((JsonNode rs) -> revision(rs)).reversed());
        JsonNode target = owned.stream()
                .filter(rs -> toRevision > 0 ? revision(rs) == toRevision : revision(rs) < current)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No revision " + (toRevision > 0 ? toRevision : "before " + current)
                        + " found for " + name + ". Available: "
                        + owned.stream().map(rs -> String.valueOf(revision(rs))).collect(Collectors.joining(", "))));

        ObjectNode template = target.path("spec").path("template").deepCopy();
        JsonNode labels = template.path("metadata").path("labels");
        if (labels instanceof ObjectNode l) {
            l.remove("pod-template-hash");
        }
        ArrayNode ops = HttpSupport.JSON.createArrayNode();
        ops.addObject().put("op", "replace").put("path", "/spec/template").set("value", template);
        patch(deployPath(ns, name), "application/json-patch+json", ops.toString());
        return Map.of("deployment", name, "fromRevision", current, "toRevision", revision(target),
                "images", images(template.path("spec")));
    }

    // ------------------------------------------------------------------ pods, logs, events

    public List<Map<String, Object>> pods(String ns, String labelSelector) {
        String q = labelSelector == null || labelSelector.isBlank() ? "" : "?labelSelector=" + HttpSupport.enc(labelSelector);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode p : getJson("/api/v1/namespaces/" + seg(ns) + "/pods" + q).path("items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.path("metadata").path("name").asText());
            m.put("phase", p.path("status").path("phase").asText());
            int ready = 0;
            int total = 0;
            int restarts = 0;
            List<String> waiting = new ArrayList<>();
            for (JsonNode cs : p.path("status").path("containerStatuses")) {
                total++;
                if (cs.path("ready").asBoolean()) {
                    ready++;
                }
                restarts += cs.path("restartCount").asInt();
                JsonNode w = cs.path("state").path("waiting");
                if (!w.isMissingNode()) {
                    waiting.add(cs.path("name").asText() + ": " + w.path("reason").asText());
                }
            }
            m.put("ready", ready + "/" + total);
            m.put("restarts", restarts);
            if (!waiting.isEmpty()) {
                m.put("waiting", waiting);
            }
            m.put("node", p.path("spec").path("nodeName").asText());
            m.put("images", images(p.path("spec")));
            m.put("started", p.path("status").path("startTime").asText());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> deploymentPods(String ns, String deployment) {
        return pods(ns, selector(deploymentRaw(ns, deployment)));
    }

    public String logs(String ns, String pod, String container, int tailLines, boolean previous) {
        StringBuilder q = new StringBuilder("?tailLines=").append(Math.max(1, tailLines));
        if (container != null && !container.isBlank()) {
            q.append("&container=").append(HttpSupport.enc(container));
        }
        if (previous) {
            q.append("&previous=true");
        }
        Resp r = HttpSupport.send(http, request("/api/v1/namespaces/" + seg(ns) + "/pods/" + seg(pod) + "/log" + q).GET().build());
        if (!r.ok()) {
            throw HttpSupport.failure("Logs of " + ns + "/" + pod, r);
        }
        return r.body();
    }

    public List<Map<String, Object>> events(String ns, String objectName, int limit) {
        String q = objectName == null || objectName.isBlank() ? ""
                : "?fieldSelector=" + HttpSupport.enc("involvedObject.name=" + objectName);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode e : getJson("/api/v1/namespaces/" + seg(ns) + "/events" + q).path("items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.path("lastTimestamp").asText(e.path("eventTime").asText()));
            m.put("type", e.path("type").asText());
            m.put("reason", e.path("reason").asText());
            m.put("object", e.path("involvedObject").path("kind").asText() + "/" + e.path("involvedObject").path("name").asText());
            m.put("message", e.path("message").asText());
            m.put("count", e.path("count").asInt(1));
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("time")).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Object> summarize(JsonNode d) {
        JsonNode meta = d.path("metadata");
        JsonNode spec = d.path("spec");
        JsonNode st = d.path("status");
        int desired = spec.path("replicas").asInt(1);
        int updated = st.path("updatedReplicas").asInt(0);
        int ready = st.path("readyReplicas").asInt(0);
        int available = st.path("availableReplicas").asInt(0);
        int unavailable = st.path("unavailableReplicas").asInt(0);
        boolean observed = st.path("observedGeneration").asLong(0) >= meta.path("generation").asLong(0);

        String rollout = "progressing";
        for (JsonNode c : st.path("conditions")) {
            if ("Progressing".equals(c.path("type").asText()) && "ProgressDeadlineExceeded".equals(c.path("reason").asText())) {
                rollout = "failed";
            }
        }
        if (!"failed".equals(rollout) && observed && updated == desired && available == desired
                && st.path("replicas").asInt(0) == desired) {
            rollout = "complete";
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", meta.path("name").asText());
        m.put("namespace", meta.path("namespace").asText());
        m.put("revision", meta.path("annotations").path(REVISION).asText("?"));
        m.put("rollout", rollout);
        m.put("replicas", Map.of("desired", desired, "updated", updated, "ready", ready,
                "available", available, "unavailable", unavailable));
        m.put("images", images(spec.path("template").path("spec")));
        List<String> conditions = new ArrayList<>();
        for (JsonNode c : st.path("conditions")) {
            conditions.add(c.path("type").asText() + "=" + c.path("status").asText()
                    + (c.hasNonNull("message") ? " (" + c.path("message").asText() + ")" : ""));
        }
        m.put("conditions", conditions);
        return m;
    }

    static Map<String, String> images(JsonNode podSpec) {
        Map<String, String> m = new LinkedHashMap<>();
        podSpec.path("containers").forEach(c -> m.put(c.path("name").asText(), c.path("image").asText()));
        return m;
    }

    static String selector(JsonNode deployment) {
        List<String> parts = new ArrayList<>();
        deployment.path("spec").path("selector").path("matchLabels").properties()
                .forEach(e -> parts.add(e.getKey() + "=" + e.getValue().asText()));
        if (parts.isEmpty()) {
            throw new IllegalStateException("Deployment has no matchLabels selector");
        }
        return String.join(",", parts);
    }

    private static int revision(JsonNode rs) {
        return rs.path("metadata").path("annotations").path(REVISION).asInt(0);
    }

    private static String deployPath(String ns, String name) {
        return "/apis/apps/v1/namespaces/" + seg(ns) + "/deployments/" + seg(name);
    }

    private static String seg(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("name/namespace is required");
        }
        return HttpSupport.enc(s);
    }

    private JsonNode getJson(String path) {
        Resp r = HttpSupport.send(http, request(path).GET().build());
        if (!r.ok()) {
            throw HttpSupport.failure("Kubernetes " + name() + " GET " + path.replaceAll("\\?.*", ""), r);
        }
        return r.json();
    }

    private void patch(String path, String contentType, String body) {
        Resp r = HttpSupport.send(http, request(path).header("Content-Type", contentType)
                .method("PATCH", BodyPublishers.ofString(body)).build());
        if (!r.ok()) {
            throw HttpSupport.failure("Kubernetes " + name() + " PATCH " + path, r);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(HttpSupport.stripSlash(cfg.apiServer()) + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token.get());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
