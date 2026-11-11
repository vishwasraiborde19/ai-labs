package com.ailabs.devops.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.ConfigStore;
import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;
import com.ailabs.devops.config.Systems;
import com.ailabs.devops.docker.DockerHubClient;
import com.ailabs.devops.jenkins.JenkinsClient;
import com.ailabs.devops.kubernetes.KubeClient;

/**
 * The named Jenkins servers, registries and clusters from {@code systems.yml}, and a lazily built
 * client for each. A blank name selects the first one configured. Clients are rebuilt when the
 * config changes (e.g. saved in the admin UI). Credentials are resolved per request through
 * {@link SecretResolver}, so rotating a secret on the access server takes effect after the cache TTL.
 */
@Component
public class Resources {

    private final ConfigStore store;
    private final SecretResolver secrets;
    private final Map<String, JenkinsClient> jenkins = new ConcurrentHashMap<>();
    private final Map<String, KubeClient> kube = new ConcurrentHashMap<>();
    private final Map<String, DockerHubClient> docker = new ConcurrentHashMap<>();
    private volatile long seenGeneration = -1;

    public Resources(ConfigStore store, SecretResolver secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    public Systems systems() {
        return store.current();
    }

    public JenkinsServer jenkinsConfig(String name) {
        return pick(systems().jenkins(), JenkinsServer::name, name, "jenkins");
    }

    public KubeCluster kubeConfig(String name) {
        return pick(systems().kubernetes(), KubeCluster::name, name, "kubernetes");
    }

    public DockerRegistry dockerConfig(String name) {
        return pick(systems().dockerhub(), DockerRegistry::name, name, "dockerhub");
    }

    public JenkinsClient jenkins(String name) {
        refreshIfChanged();
        JenkinsServer c = jenkinsConfig(name);
        return jenkins.computeIfAbsent(c.name(), k -> newJenkins(c));
    }

    public KubeClient kube(String name) {
        refreshIfChanged();
        KubeCluster c = kubeConfig(name);
        return kube.computeIfAbsent(c.name(), k -> newKube(c));
    }

    /** Docker Hub works anonymously for public repos when no registry is configured. */
    public DockerHubClient dockerhub(String name) {
        refreshIfChanged();
        if (systems().dockerhub().isEmpty() && (name == null || name.isBlank())) {
            return docker.computeIfAbsent("__anonymous", k -> new DockerHubClient(null, () -> null));
        }
        DockerRegistry c = dockerConfig(name);
        return docker.computeIfAbsent(c.name(), k -> newDocker(c));
    }

    /** Clients for a definition that may not be saved yet (admin UI "Test connection"). */
    public JenkinsClient newJenkins(JenkinsServer c) {
        return new JenkinsClient(c, () -> secrets.resolve(c.tokenRef(), "jenkins:" + c.name()),
                secrets.resolveOptional(c.caCertRef(), "jenkins:" + c.name() + ":ca"));
    }

    public KubeClient newKube(KubeCluster c) {
        return new KubeClient(c, () -> secrets.resolve(c.tokenRef(), "kubernetes:" + c.name()),
                secrets.resolveOptional(c.caCertRef(), "kubernetes:" + c.name() + ":ca"));
    }

    public DockerHubClient newDocker(DockerRegistry c) {
        return new DockerHubClient(c, () -> secrets.resolveOptional(c.passwordRef(), "dockerhub:" + c.name()));
    }

    public void requireWritableJenkins(String name) {
        JenkinsServer c = jenkinsConfig(name);
        if (c.readOnly()) {
            throw new IllegalStateException("Jenkins '" + c.name() + "' is configured read-only");
        }
    }

    public KubeCluster requireNamespace(String cluster, String namespace, boolean write) {
        KubeCluster c = kubeConfig(cluster);
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (!c.namespaceAllowed(namespace)) {
            throw new IllegalStateException("Namespace '" + namespace + "' is not in allowed-namespaces of cluster '"
                    + c.name() + "': " + c.allowedNamespaces());
        }
        if (write && c.readOnly()) {
            throw new IllegalStateException("Cluster '" + c.name() + "' is configured read-only");
        }
        return c;
    }

    /** Forget built clients, e.g. after the secret cache was cleared. */
    public void reset() {
        jenkins.clear();
        kube.clear();
        docker.clear();
    }

    private void refreshIfChanged() {
        long g = store.generation();
        if (g != seenGeneration) {
            reset();
            seenGeneration = g;
        }
    }

    static <T> T pick(List<T> items, Function<T, String> nameOf, String name, String kind) {
        if (items.isEmpty()) {
            throw new IllegalStateException("No " + kind + " systems configured (add one in the admin UI or systems.yml)");
        }
        if (name == null || name.isBlank()) {
            return items.get(0);
        }
        return items.stream().filter(i -> name.equals(nameOf.apply(i))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown " + kind + " '" + name + "'. Configured: "
                        + items.stream().map(nameOf).collect(Collectors.joining(", "))));
    }
}
