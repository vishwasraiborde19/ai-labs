package com.ailabs.kube.k8s;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The resource types a cluster serves (what {@code kubectl api-resources} shows), built from API
 * discovery. Turns user input such as {@code deploy}, {@code Deployment}, {@code deployments.apps}
 * or a manifest's {@code apiVersion}/{@code kind} into a URL.
 */
public final class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);

    private final KubeClient client;
    private final List<ApiResource> resources;
    private final Map<String, List<ApiResource>> byGroupVersion = new ConcurrentHashMap<>();

    Catalog(KubeClient client, List<ApiResource> resources) {
        this.client = client;
        this.resources = List.copyOf(resources);
    }

    /** Core v1 plus the preferred version of every API group. Groups that fail (e.g. a down metrics server) are skipped. */
    static Catalog discover(KubeClient k) {
        List<ApiResource> out = new ArrayList<>(parse("", "v1", k.get("/api/v1")));
        for (JsonNode g : k.get("/apis").path("groups")) {
            JsonNode pv = g.path("preferredVersion");
            String gv = pv.path("groupVersion").asText();
            try {
                out.addAll(parse(g.path("name").asText(), pv.path("version").asText(), k.get("/apis/" + gv)));
            } catch (RuntimeException e) {
                log.warn("discovery of {} on {} failed: {}", gv, k.name(), e.getMessage());
            }
        }
        return new Catalog(k, out);
    }

    static List<ApiResource> parse(String group, String version, JsonNode list) {
        List<ApiResource> out = new ArrayList<>();
        for (JsonNode r : list.path("resources")) {
            String name = r.path("name").asText();
            if (name.isEmpty() || name.contains("/")) {
                continue; // subresources such as pods/log
            }
            List<String> verbs = new ArrayList<>();
            r.path("verbs").forEach(v -> verbs.add(v.asText()));
            List<String> shorts = new ArrayList<>();
            r.path("shortNames").forEach(v -> shorts.add(v.asText()));
            out.add(new ApiResource(group, version, r.path("kind").asText(), name, r.path("singularName").asText(""),
                    r.path("namespaced").asBoolean(), verbs, shorts));
        }
        return out;
    }

    public List<ApiResource> all() {
        return resources;
    }

    /**
     * @param type       kind, plural, singular or short name, optionally with {@code .group}
     *                   ({@code deploy}, {@code Deployment}, {@code ingresses.networking.k8s.io})
     * @param apiVersion optional, e.g. {@code apps/v1}, to pick a non-preferred version
     */
    public ApiResource resolve(String type, String apiVersion) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("kind is required (e.g. pods, deployment, svc, nodes)");
        }
        String t = type.trim();
        String group = null;
        int dot = t.indexOf('.');
        if (dot > 0) {
            group = t.substring(dot + 1).toLowerCase(Locale.ROOT);
            t = t.substring(0, dot);
        }
        String want = t.toLowerCase(Locale.ROOT);
        String g = group;
        List<ApiResource> pool = apiVersion == null || apiVersion.isBlank() ? resources : groupVersion(apiVersion.trim());
        return pool.stream()
                .filter(r -> g == null || r.group().equals(g))
                .filter(r -> r.matches(want))
                .min(Comparator.comparingInt(Catalog::rank))
                .orElseThrow(() -> new IllegalArgumentException("Unknown resource type '" + type + "'"
                        + (apiVersion == null || apiVersion.isBlank() ? "" : " in " + apiVersion)
                        + ". k8s_api_resources lists what this cluster serves."));
    }

    /** The type behind a manifest's {@code apiVersion} and {@code kind}. */
    public ApiResource forManifest(String apiVersion, String kind) {
        return groupVersion(apiVersion).stream().filter(r -> r.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The cluster does not serve kind " + kind + " in "
                        + apiVersion + " (wrong apiVersion, or the CRD is not installed?)"));
    }

    List<ApiResource> groupVersion(String apiVersion) {
        return byGroupVersion.computeIfAbsent(apiVersion, gv -> {
            List<ApiResource> known = resources.stream().filter(r -> r.apiVersion().equals(gv)).toList();
            if (!known.isEmpty()) {
                return known;
            }
            int slash = gv.indexOf('/');
            JsonNode list = client.getOrNull(slash < 0 ? "/api/" + gv : "/apis/" + gv);
            if (list == null) {
                throw new IllegalArgumentException("The cluster does not serve apiVersion " + gv);
            }
            return parse(slash < 0 ? "" : gv.substring(0, slash), slash < 0 ? gv : gv.substring(slash + 1), list);
        });
    }

    /** On a name clash prefer core, then apps, then add-on groups, then other built-in groups. */
    static int rank(ApiResource r) {
        if (r.group().isEmpty()) {
            return 0;
        }
        if (r.group().equals("apps")) {
            return 1;
        }
        return r.group().endsWith(".k8s.io") ? 3 : 2;
    }
}
