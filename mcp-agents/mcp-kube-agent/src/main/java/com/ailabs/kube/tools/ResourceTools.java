package com.ailabs.kube.tools;

import static com.ailabs.kube.tools.Params.API_VERSION;
import static com.ailabs.kube.tools.Params.CLUSTER;
import static com.ailabs.kube.tools.Params.DRY_RUN;
import static com.ailabs.kube.tools.Params.KIND;
import static com.ailabs.kube.tools.Params.NAMESPACE;
import static com.ailabs.kube.tools.Params.blank;
import static com.ailabs.kube.tools.Params.clamp;
import static com.ailabs.kube.tools.Params.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Skill: read and change any object in the cluster, like kubectl get / describe / apply / patch / delete. */
@Component
public class ResourceTools {

    static final String FIELD_MANAGER = "mcp-kube-agent";
    private static final int MAX_DOCS = 50;
    private static final Set<String> WORKLOADS = Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job");

    private final Clusters clusters;

    public ResourceTools(Clusters clusters) {
        this.clusters = clusters;
    }

    @Tool(name = "k8s_list", description = "List objects of any type (pods, deployments, services, nodes, pvc, CRDs, ...) "
            + "with a one-line status each, like kubectl get. Omit namespace to list across every namespace the agent may read.")
    public Map<String, Object> list(@ToolParam(required = false, description = CLUSTER) String cluster,
                                    @ToolParam(description = KIND) String kind,
                                    @ToolParam(required = false, description = "Namespace; omit for all namespaces") String namespace,
                                    @ToolParam(required = false, description = "Label selector, e.g. app=web,tier!=db") String labelSelector,
                                    @ToolParam(required = false, description = "Field selector, e.g. status.phase=Running or spec.nodeName=node1") String fieldSelector,
                                    @ToolParam(required = false, description = "Max objects, default 200") Integer limit,
                                    @ToolParam(required = false, description = API_VERSION) String apiVersion) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(kind, apiVersion);
        int max = clamp(limit, 200, 1, 2000);
        List<String> scopes = r.namespaced() ? clusters.scope(c, namespace) : Collections.singletonList(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean more = false;
        for (String ns : scopes) {
            int left = max - rows.size();
            if (left <= 0) {
                more = true;
                break;
            }
            JsonNode list = k.get(r.collectionPath(ns)
                    + query("labelSelector", labelSelector, "fieldSelector", fieldSelector, "limit", String.valueOf(left)));
            list.path("items").forEach(o -> rows.add(KubeObjects.row(r.kind(), o)));
            if (!list.path("metadata").path("continue").asText("").isEmpty()) {
                more = true;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", r.display());
        out.put("namespace", r.namespaced() ? (blank(namespace) ? "(all allowed)" : namespace) : "(cluster-scoped)");
        out.put("count", rows.size());
        if (more) {
            out.put("truncated", "more objects exist; narrow with a selector or raise limit");
        }
        out.put("items", rows);
        return out;
    }

    @Tool(name = "k8s_get", description = "One object as YAML (default), JSON or a one-line summary, like kubectl get -o yaml. "
            + "managedFields are dropped and Secret values are always hidden (only their sizes are shown).")
    public String get(@ToolParam(required = false, description = CLUSTER) String cluster,
                      @ToolParam(description = KIND) String kind,
                      @ToolParam(description = "Object name") String name,
                      @ToolParam(required = false, description = "Namespace (required for namespaced types)") String namespace,
                      @ToolParam(required = false, description = "yaml (default), json or summary") String format,
                      @ToolParam(required = false, description = API_VERSION) String apiVersion) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(kind, apiVersion);
        String ns = r.namespaced() ? namespace : null;
        clusters.checkRead(c, r, requireNs(r, ns));
        JsonNode o = k.get(r.itemPath(ns, name));
        String f = blank(format) ? "yaml" : format.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "json" -> KubeObjects.clean(o, r.kind()).toPrettyString();
            case "summary" -> KubeObjects.yaml(HttpSupport.JSON.valueToTree(KubeObjects.row(r.kind(), o)));
            default -> KubeObjects.yaml(KubeObjects.clean(o, r.kind()));
        };
    }

    @Tool(name = "k8s_describe", description = "Explain the state of one object, like kubectl describe: summary, conditions, "
            + "container states (pods), the pods behind a workload, and the object's recent events.")
    public Map<String, Object> describe(@ToolParam(required = false, description = CLUSTER) String cluster,
                                        @ToolParam(description = KIND) String kind,
                                        @ToolParam(description = "Object name") String name,
                                        @ToolParam(required = false, description = "Namespace (required for namespaced types)") String namespace,
                                        @ToolParam(required = false, description = API_VERSION) String apiVersion) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(kind, apiVersion);
        String ns = r.namespaced() ? namespace : null;
        clusters.checkRead(c, r, requireNs(r, ns));
        JsonNode o = k.get(r.itemPath(ns, name));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", r.kind());
        out.put("summary", KubeObjects.row(r.kind(), o));
        JsonNode meta = o.path("metadata");
        if (meta.has("labels")) {
            out.put("labels", meta.path("labels"));
        }
        List<String> owners = new ArrayList<>();
        meta.path("ownerReferences").forEach(ref -> owners.add(ref.path("kind").asText() + "/" + ref.path("name").asText()));
        if (!owners.isEmpty()) {
            out.put("ownedBy", owners);
        }
        List<String> conditions = KubeObjects.conditions(o.path("status").path("conditions"));
        if (!conditions.isEmpty()) {
            out.put("conditions", conditions);
        }
        if (r.kind().equals("Pod")) {
            out.put("containers", containers(o));
        }
        if (WORKLOADS.contains(r.kind()) && r.group().matches("apps|batch")) {
            List<Map<String, Object>> pods = new ArrayList<>();
            k.get("/api/v1/namespaces/" + HttpSupport.enc(ns) + "/pods"
                    + query("labelSelector", KubeObjects.workloadSelector(o), "limit", "30"))
                    .path("items").forEach(p -> pods.add(KubeObjects.row("Pod", p)));
            out.put("pods", pods);
        }
        if (r.kind().equals("Service") && o.path("spec").has("selector")) {
            JsonNode eps = k.getOrNull("/api/v1/namespaces/" + HttpSupport.enc(ns) + "/endpoints/" + HttpSupport.enc(name));
            List<String> ready = new ArrayList<>();
            List<String> notReady = new ArrayList<>();
            if (eps != null) {
                for (JsonNode s : eps.path("subsets")) {
                    s.path("addresses").forEach(a -> ready.add(a.path("ip").asText() + " (" + a.path("targetRef").path("name").asText() + ")"));
                    s.path("notReadyAddresses").forEach(a -> notReady.add(a.path("ip").asText() + " (" + a.path("targetRef").path("name").asText() + ")"));
                }
            }
            out.put("endpoints", Map.of("ready", ready, "notReady", notReady));
        }
        out.put("events", objectEvents(c, k, r.kind(), name, ns));
        return out;
    }

    @Tool(name = "k8s_apply", description = "Create or update objects from YAML or JSON manifests (one or more documents "
            + "separated by ---), like kubectl apply --server-side. Use dryRun=true first to validate. Reports created, "
            + "configured or unchanged per object.")
    public Map<String, Object> apply(@ToolParam(required = false, description = CLUSTER) String cluster,
                                     @ToolParam(description = "Manifest(s), YAML or JSON") String manifest,
                                     @ToolParam(required = false, description = "Namespace for namespaced objects that do not set one") String namespace,
                                     @ToolParam(required = false, description = DRY_RUN) Boolean dryRun,
                                     @ToolParam(required = false, description = "Take ownership of fields managed by others (kubectl --force-conflicts)") Boolean force) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        List<ObjectNode> docs = parseManifests(manifest);

        // Check everything before changing anything.
        record Item(ApiResource r, String ns, String name, ObjectNode body) {}
        List<Item> items = new ArrayList<>();
        for (ObjectNode d : docs) {
            String apiVersion = d.path("apiVersion").asText("");
            String kind = d.path("kind").asText("");
            String name = d.path("metadata").path("name").asText("");
            if (apiVersion.isEmpty() || kind.isEmpty() || name.isEmpty()) {
                throw new IllegalArgumentException("Every document needs apiVersion, kind and metadata.name"
                        + (kind.isEmpty() ? "" : " (" + kind + " " + name + ")"));
            }
            if (d.has("metadata") && d.path("metadata").has("generateName")) {
                throw new IllegalArgumentException(kind + " uses generateName, which apply does not support");
            }
            ApiResource r = k.catalog().forManifest(apiVersion, kind);
            String ns = null;
            if (r.namespaced()) {
                ns = d.path("metadata").path("namespace").asText(blank(namespace) ? "default" : namespace);
                ((ObjectNode) d.path("metadata")).put("namespace", ns);
            } else if (d.path("metadata") instanceof ObjectNode m) {
                m.remove("namespace");
            }
            clusters.checkWrite(c, r, ns, name);
            items.add(new Item(r, ns, name, d));
        }

        boolean dry = Boolean.TRUE.equals(dryRun);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Item it : items) {
            String path = it.r().itemPath(it.ns(), it.name());
            JsonNode before = k.getOrNull(path);
            JsonNode after = k.patch(path + query("fieldManager", FIELD_MANAGER, "force", Boolean.TRUE.equals(force) ? "true" : null,
                    "dryRun", dry ? "All" : null), "application/apply-patch+yaml", it.body().toString());
            String action = before == null ? "created"
                    : before.path("metadata").path("resourceVersion").asText().equals(after.path("metadata").path("resourceVersion").asText())
                    ? "unchanged" : "configured";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("object", it.r().display() + "/" + it.name());
            if (it.ns() != null) {
                m.put("namespace", it.ns());
            }
            m.put("result", action + (dry ? " (dry run)" : ""));
            results.add(m);
        }
        return Map.of("dryRun", dry, "results", results);
    }

    @Tool(name = "k8s_patch", description = "Patch one object (kubectl patch). The patch may be JSON or YAML. "
            + "patchType: merge (default), strategic (built-in types; merges container lists by name) or json (RFC 6902 ops).")
    public Map<String, Object> patch(@ToolParam(required = false, description = CLUSTER) String cluster,
                                     @ToolParam(description = KIND) String kind,
                                     @ToolParam(description = "Object name") String name,
                                     @ToolParam(required = false, description = "Namespace (required for namespaced types)") String namespace,
                                     @ToolParam(description = "Patch body, e.g. {\"spec\":{\"replicas\":3}}") String patch,
                                     @ToolParam(required = false, description = "merge, strategic or json") String patchType,
                                     @ToolParam(required = false, description = DRY_RUN) Boolean dryRun,
                                     @ToolParam(required = false, description = API_VERSION) String apiVersion) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(kind, apiVersion);
        String ns = r.namespaced() ? namespace : null;
        clusters.checkWrite(c, r, ns, name);
        String type = blank(patchType) ? "merge" : patchType.toLowerCase(Locale.ROOT);
        String contentType = switch (type) {
            case "merge" -> "application/merge-patch+json";
            case "strategic" -> "application/strategic-merge-patch+json";
            case "json" -> "application/json-patch+json";
            default -> throw new IllegalArgumentException("patchType must be merge, strategic or json");
        };
        JsonNode body;
        try {
            body = KubeObjects.YAML.readTree(patch);
        } catch (IOException e) {
            throw new IllegalArgumentException("patch is not valid JSON/YAML: " + e.getMessage());
        }
        boolean dry = Boolean.TRUE.equals(dryRun);
        JsonNode after = k.patch(r.itemPath(ns, name) + query("fieldManager", FIELD_MANAGER, "dryRun", dry ? "All" : null),
                contentType, body.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patched", r.display() + "/" + name + (dry ? " (dry run)" : ""));
        out.put("summary", KubeObjects.row(r.kind(), after));
        return out;
    }

    @Tool(name = "k8s_delete", description = "Delete one object. confirm must repeat the object name. "
            + "Use dryRun=true to check first. Deleting a namespace deletes everything in it.")
    public Map<String, Object> delete(@ToolParam(required = false, description = CLUSTER) String cluster,
                                      @ToolParam(description = KIND) String kind,
                                      @ToolParam(description = "Object name") String name,
                                      @ToolParam(required = false, description = "Namespace (required for namespaced types)") String namespace,
                                      @ToolParam(description = "Repeat the object name to confirm the deletion") String confirm,
                                      @ToolParam(required = false, description = DRY_RUN) Boolean dryRun,
                                      @ToolParam(required = false, description = "Background (default), Foreground or Orphan") String propagation,
                                      @ToolParam(required = false, description = API_VERSION) String apiVersion) {
        if (blank(name) || !name.equals(confirm)) {
            throw new IllegalArgumentException("Refusing to delete: confirm must equal the object name '" + name + "'");
        }
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        ApiResource r = k.catalog().resolve(kind, apiVersion);
        String ns = r.namespaced() ? namespace : null;
        clusters.checkWrite(c, r, ns, name);
        String policy = blank(propagation) ? "Background" : propagation;
        if (!Set.of("Background", "Foreground", "Orphan").contains(policy)) {
            throw new IllegalArgumentException("propagation must be Background, Foreground or Orphan");
        }
        boolean dry = Boolean.TRUE.equals(dryRun);
        ObjectNode opts = HttpSupport.JSON.createObjectNode();
        opts.put("apiVersion", "v1").put("kind", "DeleteOptions").put("propagationPolicy", policy);
        if (dry) {
            opts.putArray("dryRun").add("All");
        }
        k.delete(r.itemPath(ns, name), opts.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", r.display() + "/" + name + (dry ? " (dry run, nothing deleted)" : ""));
        if (ns != null) {
            out.put("namespace", ns);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static String requireNs(ApiResource r, String ns) {
        if (r.namespaced() && blank(ns)) {
            throw new IllegalArgumentException("namespace is required for " + r.display());
        }
        return ns;
    }

    static List<ObjectNode> parseManifests(String manifest) {
        if (blank(manifest)) {
            throw new IllegalArgumentException("manifest is empty");
        }
        List<ObjectNode> docs = new ArrayList<>();
        try (MappingIterator<JsonNode> it = KubeObjects.YAML.readerFor(JsonNode.class).readValues(manifest)) {
            while (it.hasNext()) {
                JsonNode d = it.next();
                if (d == null || d.isNull() || d.isMissingNode() || (d.isObject() && d.isEmpty())) {
                    continue;
                }
                if (!d.isObject()) {
                    throw new IllegalArgumentException("Each manifest document must be an object");
                }
                if (d.path("kind").asText().endsWith("List") && d.has("items")) {
                    d.path("items").forEach(i -> docs.add((ObjectNode) i));
                } else {
                    docs.add((ObjectNode) d);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Manifest is not valid YAML/JSON: " + e.getMessage());
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("manifest has no objects");
        }
        if (docs.size() > MAX_DOCS) {
            throw new IllegalArgumentException("At most " + MAX_DOCS + " objects per apply");
        }
        return docs;
    }

    static List<Map<String, Object>> containers(JsonNode pod) {
        Map<String, JsonNode> statuses = new LinkedHashMap<>();
        pod.path("status").path("initContainerStatuses").forEach(s -> statuses.put("init:" + s.path("name").asText(), s));
        pod.path("status").path("containerStatuses").forEach(s -> statuses.put(s.path("name").asText(), s));
        List<Map<String, Object>> out = new ArrayList<>();
        List<JsonNode> specs = new ArrayList<>();
        pod.path("spec").path("initContainers").forEach(specs::add);
        int inits = specs.size();
        pod.path("spec").path("containers").forEach(specs::add);
        for (int i = 0; i < specs.size(); i++) {
            JsonNode spec = specs.get(i);
            String key = (i < inits ? "init:" : "") + spec.path("name").asText();
            JsonNode s = statuses.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", key);
            m.put("image", spec.path("image").asText());
            if (spec.has("resources")) {
                m.put("resources", spec.path("resources"));
            }
            if (s != null) {
                m.put("ready", s.path("ready").asBoolean());
                m.put("restarts", s.path("restartCount").asInt());
                m.put("state", state(s.path("state")));
                if (!s.path("lastState").isEmpty()) {
                    m.put("lastState", state(s.path("lastState")));
                }
            }
            out.add(m);
        }
        return out;
    }

    static String state(JsonNode st) {
        if (st.has("running")) {
            return "running since " + st.path("running").path("startedAt").asText();
        }
        if (st.has("waiting")) {
            JsonNode w = st.path("waiting");
            return "waiting: " + w.path("reason").asText() + (w.has("message") ? " - " + HttpSupport.truncate(w.path("message").asText(), 400) : "");
        }
        if (st.has("terminated")) {
            JsonNode t = st.path("terminated");
            return "terminated: " + t.path("reason").asText() + " exitCode=" + t.path("exitCode").asInt()
                    + " at " + t.path("finishedAt").asText() + (t.has("message") ? " - " + HttpSupport.truncate(t.path("message").asText(), 400) : "");
        }
        return "unknown";
    }

    /** Events about one object, newest first (cluster-scoped objects' events are read cluster-wide when allowed). */
    List<Map<String, Object>> objectEvents(Cluster c, KubeClient k, String kind, String name, String ns) {
        String base = ns != null ? "/api/v1/namespaces/" + HttpSupport.enc(ns) + "/events" : "/api/v1/events";
        if (ns == null && !c.allowedNamespaces().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        k.get(base + query("fieldSelector", "involvedObject.name=" + name + ",involvedObject.kind=" + kind))
                .path("items").forEach(e -> out.add(DiagnoseTools.eventRow(e)));
        out.sort((a, b) -> ((String) b.get("time")).compareTo((String) a.get("time")));
        return out.size() > 25 ? out.subList(0, 25) : out;
    }
}
