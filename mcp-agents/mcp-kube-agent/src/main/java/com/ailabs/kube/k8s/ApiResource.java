package com.ailabs.kube.k8s;

import java.util.List;
import java.util.Locale;

import com.ailabs.kube.http.HttpSupport;

/** One resource type served by the API server, as found by discovery (e.g. deployments.apps/v1). */
public record ApiResource(String group, String version, String kind, String plural, String singular,
                          boolean namespaced, List<String> verbs, List<String> shortNames) {

    public ApiResource {
        verbs = verbs != null ? List.copyOf(verbs) : List.of();
        shortNames = shortNames != null ? List.copyOf(shortNames) : List.of();
        singular = singular == null || singular.isBlank() ? kind.toLowerCase(Locale.ROOT) : singular;
    }

    public String apiVersion() {
        return group.isEmpty() ? version : group + "/" + version;
    }

    /** {@code pods} or {@code deployments.apps}, the way kubectl names types. */
    public String display() {
        return group.isEmpty() ? plural : plural + "." + group;
    }

    public boolean matches(String lowerName) {
        return plural.equals(lowerName) || singular.equals(lowerName)
                || kind.toLowerCase(Locale.ROOT).equals(lowerName) || shortNames.contains(lowerName);
    }

    /** Collection URL; for a namespaced type with {@code ns == null} it lists across all namespaces. */
    public String collectionPath(String ns) {
        String base = group.isEmpty() ? "/api/" + version : "/apis/" + group + "/" + version;
        return namespaced && ns != null ? base + "/namespaces/" + seg(ns) + "/" + plural : base + "/" + plural;
    }

    public String itemPath(String ns, String name) {
        if (namespaced && (ns == null || ns.isBlank())) {
            throw new IllegalArgumentException("namespace is required for " + display());
        }
        return collectionPath(namespaced ? ns : null) + "/" + seg(name);
    }

    static String seg(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return HttpSupport.enc(s);
    }
}
