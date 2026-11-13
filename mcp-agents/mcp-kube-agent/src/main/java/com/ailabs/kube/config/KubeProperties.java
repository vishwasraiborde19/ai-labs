package com.ailabs.kube.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code kube:} section of the config file. No field holds a secret: fields ending in
 * {@code Ref} hold a reference such as {@code path:~/.kube-agent/lab.token} or {@code env:K8S_TOKEN},
 * resolved by {@link com.ailabs.kube.access.SecretResolver} when needed.
 */
@ConfigurationProperties(prefix = "kube")
public record KubeProperties(Access access, List<Cluster> clusters, Http http) {

    public static final List<String> DEFAULT_PROTECTED = List.of("kube-system", "kube-public", "kube-node-lease");
    public static final String SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount";

    public KubeProperties {
        access = access != null ? access : new Access(null, null);
        clusters = clusters != null ? List.copyOf(clusters) : List.of();
        http = http != null ? http : new Http(null);
    }

    /** {@code cacheTtl}: how long resolved secrets are kept (0 disables). */
    public record Access(Duration cacheTtl, String secretsFile) {
        public Access {
            cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofMinutes(5);
            secretsFile = blank(secretsFile) ? "~/.kube-agent/secrets.yml" : secretsFile;
        }
    }

    /**
     * One cluster the agent may manage.
     *
     * @param inCluster           the agent runs in a pod of this cluster: API server, token and CA come
     *                            from the pod's service account mount
     * @param readOnly            refuse every change
     * @param allowedNamespaces   namespaces the agent may read and change; empty means all
     * @param protectedNamespaces namespaces the agent may read but never change (default kube-system,
     *                            kube-public, kube-node-lease; {@code []} protects none)
     * @param clusterScopedWrites allow changes to cluster-scoped objects (nodes, namespaces, CRDs,
     *                            storage classes, ...) and to RBAC objects; off by default
     */
    public record Cluster(String name, String apiServer, String tokenRef, String caCertRef,
                          boolean insecureSkipTlsVerify, boolean inCluster, boolean readOnly,
                          List<String> allowedNamespaces, List<String> protectedNamespaces,
                          boolean clusterScopedWrites) {
        public Cluster {
            if (inCluster) {
                name = blank(name) ? "in-cluster" : name;
                String host = System.getenv("KUBERNETES_SERVICE_HOST");
                String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443");
                if (blank(apiServer) && host != null) {
                    apiServer = "https://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
                }
                tokenRef = blank(tokenRef) ? "path:" + SA_DIR + "/token" : tokenRef;
                caCertRef = blank(caCertRef) && !insecureSkipTlsVerify ? "path:" + SA_DIR + "/ca.crt" : caCertRef;
            }
            allowedNamespaces = allowedNamespaces != null ? List.copyOf(allowedNamespaces) : List.of();
            protectedNamespaces = protectedNamespaces != null ? List.copyOf(protectedNamespaces) : DEFAULT_PROTECTED;
        }

        public static Cluster inClusterDefault() {
            return new Cluster(null, null, null, null, false, true, false, null, null, false);
        }

        public boolean namespaceAllowed(String ns) {
            return allowedNamespaces.isEmpty() || allowedNamespaces.contains(ns);
        }

        public boolean namespaceProtected(String ns) {
            return ns != null && protectedNamespaces.contains(ns);
        }
    }

    /** Bearer token HTTP-mode MCP clients must present. Unused in stdio mode. */
    public record Http(String authTokenRef) {}

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
