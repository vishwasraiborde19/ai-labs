package com.ailabs.kube.tools;

import static com.ailabs.kube.tools.Params.CLUSTER;
import static com.ailabs.kube.tools.Params.blank;

import java.util.ArrayList;
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
import com.ailabs.kube.k8s.KubeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Skill: know which clusters the agent manages, who it is there and what it may do. */
@Component
public class ClusterTools {

    private static final Set<String> WRITE_VERBS = Set.of("create", "update", "patch", "delete", "deletecollection");

    private final Clusters clusters;

    public ClusterTools(Clusters clusters) {
        this.clusters = clusters;
    }

    @Tool(name = "k8s_clusters", description = "Clusters this agent manages and the agent policy on each "
            + "(read-only, allowed and protected namespaces, cluster-scoped writes). Start here.")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Cluster c : clusters.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("apiServer", c.apiServer());
            m.put("inCluster", c.inCluster());
            m.put("readOnly", c.readOnly());
            m.put("allowedNamespaces", c.allowedNamespaces().isEmpty() ? "all" : c.allowedNamespaces());
            m.put("protectedNamespaces", c.protectedNamespaces());
            m.put("clusterScopedWrites", c.clusterScopedWrites());
            out.add(m);
        }
        return out;
    }

    @Tool(name = "k8s_whoami", description = "Connect to a cluster and report the Kubernetes version and the identity "
            + "(service account, groups) the agent authenticates as. Use it to test the connection.")
    public Map<String, Object> whoami(@ToolParam(required = false, description = CLUSTER) String cluster) {
        KubeClient k = clusters.client(cluster);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster", k.name());
        out.put("apiServer", k.config().apiServer());
        JsonNode v = k.get("/version");
        out.put("version", v.path("gitVersion").asText());
        out.put("platform", v.path("platform").asText());
        String body = "{\"kind\":\"SelfSubjectReview\",\"apiVersion\":\"authentication.k8s.io/%s\"}";
        for (String ver : List.of("v1", "v1beta1", "v1alpha1")) {
            try {
                JsonNode r = k.post("/apis/authentication.k8s.io/" + ver + "/selfsubjectreviews", body.formatted(ver));
                JsonNode u = r.path("status").path("userInfo");
                out.put("user", u.path("username").asText());
                List<String> groups = new ArrayList<>();
                u.path("groups").forEach(g -> groups.add(g.asText()));
                out.put("groups", groups);
                break;
            } catch (KubeException e) {
                if (e.status() != 404) {
                    out.put("user", "unknown (" + e.getMessage() + ")");
                    break;
                }
            }
        }
        out.putIfAbsent("user", "unknown (SelfSubjectReview not served by this Kubernetes version)");
        out.put("resourceTypes", k.catalog(true).all().size());
        return out;
    }

    @Tool(name = "k8s_can_i", description = "Ask the API server whether the agent's service account may do something "
            + "(kubectl auth can-i), and whether the agent's own policy would allow it.")
    public Map<String, Object> canI(@ToolParam(required = false, description = CLUSTER) String cluster,
                                    @ToolParam(description = "Verb: get, list, watch, create, update, patch, delete") String verb,
                                    @ToolParam(description = Params.KIND) String kind,
                                    @ToolParam(required = false, description = "Namespace; omit for cluster-wide") String namespace,
                                    @ToolParam(required = false, description = "Object name") String name,
                                    @ToolParam(required = false, description = "Subresource, e.g. log, scale, eviction, exec") String subresource) {
        Cluster c = clusters.config(cluster);
        KubeClient k = clusters.client(cluster);
        String group = "";
        String resource = kind;
        ApiResource r = null;
        try {
            r = k.catalog().resolve(kind, null);
            group = r.group();
            resource = r.plural();
        } catch (IllegalArgumentException e) {
            // not served: ask about it literally
        }
        ObjectNode body = HttpSupport.JSON.createObjectNode();
        body.put("apiVersion", "authorization.k8s.io/v1").put("kind", "SelfSubjectAccessReview");
        ObjectNode attrs = body.putObject("spec").putObject("resourceAttributes");
        attrs.put("verb", verb.toLowerCase(Locale.ROOT)).put("group", group).put("resource", resource);
        if (!blank(namespace)) {
            attrs.put("namespace", namespace);
        }
        if (!blank(name)) {
            attrs.put("name", name);
        }
        if (!blank(subresource)) {
            attrs.put("subresource", subresource);
        }
        JsonNode st = k.post("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews", body.toString()).path("status");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rbacAllowed", st.path("allowed").asBoolean(false));
        if (!st.path("reason").asText("").isEmpty()) {
            out.put("reason", st.path("reason").asText());
        }
        if (r != null) {
            String policy = "allowed";
            try {
                if (WRITE_VERBS.contains(verb.toLowerCase(Locale.ROOT))) {
                    clusters.checkWrite(c, r, blank(namespace) ? null : namespace, name);
                } else {
                    clusters.checkRead(c, r, blank(namespace) ? null : namespace);
                }
            } catch (RuntimeException e) {
                policy = "blocked: " + e.getMessage();
            }
            out.put("agentPolicy", policy);
        }
        return out;
    }

    @Tool(name = "k8s_api_resources", description = "Resource types the cluster serves, including CRDs "
            + "(kubectl api-resources). Optional filter on name, kind or group.")
    public List<Map<String, Object>> apiResources(@ToolParam(required = false, description = CLUSTER) String cluster,
                                                  @ToolParam(required = false, description = "Substring to match, e.g. cert, networking, gateway") String filter,
                                                  @ToolParam(required = false, description = "Re-run discovery (after installing CRDs)") Boolean refresh) {
        KubeClient k = clusters.client(cluster);
        String f = blank(filter) ? null : filter.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiResource r : k.catalog(Boolean.TRUE.equals(refresh)).all()) {
            if (f != null && !(r.display().contains(f) || r.kind().toLowerCase(Locale.ROOT).contains(f) || r.shortNames().contains(f))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.display());
            m.put("kind", r.kind());
            m.put("apiVersion", r.apiVersion());
            m.put("namespaced", r.namespaced());
            if (!r.shortNames().isEmpty()) {
                m.put("shortNames", r.shortNames());
            }
            m.put("verbs", r.verbs());
            out.add(m);
        }
        return out;
    }
}
