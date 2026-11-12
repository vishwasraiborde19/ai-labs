package com.ailabs.kube.k8s;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ailabs.kube.access.SecretResolver;
import com.ailabs.kube.config.KubeProperties;
import com.ailabs.kube.config.KubeProperties.Cluster;

/**
 * The configured clusters, a client for each, and the agent's own policy on top of RBAC:
 * read-only clusters, allowed and protected namespaces, and whether cluster-scoped objects may change.
 * RBAC on the service account is the real boundary; this policy keeps the agent inside a smaller one.
 */
@Component
public class Clusters {

    private static final Logger log = LoggerFactory.getLogger(Clusters.class);

    private final List<Cluster> clusters;
    private final List<String> problems;
    private final SecretResolver secrets;
    private final Map<String, KubeClient> clients = new ConcurrentHashMap<>();

    public Clusters(KubeProperties props, SecretResolver secrets) {
        this.secrets = secrets;
        List<Cluster> list = props.clusters();
        if (list.isEmpty() && System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            log.info("No clusters configured and running in a pod: using the pod's service account");
            list = List.of(Cluster.inClusterDefault());
        }
        this.clusters = list;
        this.problems = validate(list);
        problems.forEach(p -> log.error("config: {}", p));
    }

    public List<Cluster> all() {
        return clusters;
    }

    public Cluster config(String name) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Fix the agent config (~/.kube-agent/config.yml): " + String.join("; ", problems));
        }
        if (clusters.isEmpty()) {
            throw new IllegalStateException("No clusters configured: add one under kube.clusters in ~/.kube-agent/config.yml");
        }
        if (name == null || name.isBlank()) {
            return clusters.get(0);
        }
        return clusters.stream().filter(c -> name.equals(c.name())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster '" + name + "'. Configured: "
                        + clusters.stream().map(Cluster::name).collect(Collectors.joining(", "))));
    }

    public KubeClient client(String name) {
        Cluster c = config(name);
        return clients.computeIfAbsent(c.name(), k -> new KubeClient(c,
                () -> secrets.resolve(c.tokenRef(), "kubernetes:" + c.name()),
                secrets.resolveOptional(c.caCertRef(), "kubernetes:" + c.name() + ":ca")));
    }

    /** Forget clients and cached secrets (after rotating a token). */
    public void reset() {
        clients.clear();
        secrets.clearCache();
    }

    // ------------------------------------------------------------------ policy

    /** Reads of a namespaced type need the namespace to be in allowed-namespaces. */
    public void checkRead(Cluster c, ApiResource r, String ns) {
        if (r != null && !r.namespaced()) {
            return;
        }
        if (ns == null) {
            if (!c.allowedNamespaces().isEmpty()) {
                throw new IllegalStateException("Cluster '" + c.name() + "' only allows namespaces "
                        + c.allowedNamespaces() + "; pass one of them");
            }
        } else if (!c.namespaceAllowed(ns)) {
            throw new IllegalStateException("Namespace '" + ns + "' is not in allowed-namespaces of cluster '"
                    + c.name() + "': " + c.allowedNamespaces());
        }
    }

    /** Throws with the reason when the agent's policy forbids changing this object. */
    public void checkWrite(Cluster c, ApiResource r, String ns, String name) {
        if (c.readOnly()) {
            throw new IllegalStateException("Cluster '" + c.name() + "' is configured read-only");
        }
        boolean rbac = r.group().equals("rbac.authorization.k8s.io");
        if (r.namespaced()) {
            if (ns == null || ns.isBlank()) {
                throw new IllegalArgumentException("namespace is required for " + r.display());
            }
            checkRead(c, r, ns);
            if (c.namespaceProtected(ns)) {
                throw new IllegalStateException("Namespace '" + ns + "' is protected on cluster '" + c.name()
                        + "' (protected-namespaces: " + c.protectedNamespaces() + "); the agent will not change it");
            }
        } else if (!c.clusterScopedWrites()) {
            throw new IllegalStateException("Changing cluster-scoped " + r.display() + " is disabled on cluster '"
                    + c.name() + "' (set cluster-scoped-writes: true in the agent config to allow it)");
        }
        if (rbac && !c.clusterScopedWrites()) {
            throw new IllegalStateException("Changing RBAC (" + r.display() + ") is disabled on cluster '" + c.name()
                    + "' (it can grant more access; set cluster-scoped-writes: true to allow it)");
        }
        if (r.kind().equals("Namespace") && r.group().isEmpty() && name != null
                && (c.namespaceProtected(name) || !c.namespaceAllowed(name))) {
            throw new IllegalStateException("Namespace '" + name + "' is protected or not allowed on cluster '" + c.name() + "'");
        }
    }

    /**
     * Namespaces to visit for a namespaced read: the given one, or every allowed namespace. A single
     * {@code null} entry means one cluster-wide call (no namespace restriction).
     */
    public List<String> scope(Cluster c, String ns) {
        if (ns != null && !ns.isBlank()) {
            if (!c.namespaceAllowed(ns)) {
                throw new IllegalStateException("Namespace '" + ns + "' is not in allowed-namespaces of cluster '"
                        + c.name() + "': " + c.allowedNamespaces());
            }
            return List.of(ns);
        }
        return c.allowedNamespaces().isEmpty() ? Collections.singletonList(null) : c.allowedNamespaces();
    }

    static List<String> validate(List<Cluster> list) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Cluster c : list) {
            if (c.name() == null || !c.name().matches("[A-Za-z0-9._-]{1,64}")) {
                errors.add("cluster name '" + c.name() + "' must be 1-64 letters, digits, . _ -");
                continue;
            }
            if (!seen.add(c.name())) {
                errors.add("cluster '" + c.name() + "' is defined twice");
            }
            if (!c.inCluster() && (c.apiServer() == null || !c.apiServer().matches("https?://.+"))) {
                errors.add("cluster '" + c.name() + "' needs api-server: https://host:6443");
            }
            if (c.tokenRef() == null || c.tokenRef().isBlank()) {
                errors.add("cluster '" + c.name() + "' needs token-ref (e.g. path:~/.kube-agent/" + c.name() + ".token)");
            }
            for (String ns : c.allowedNamespaces()) {
                if (!ns.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
                    errors.add("cluster '" + c.name() + "': '" + ns + "' is not a valid namespace");
                }
            }
        }
        return errors;
    }
}
