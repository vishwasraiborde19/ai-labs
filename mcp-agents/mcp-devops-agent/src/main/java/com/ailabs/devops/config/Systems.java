package com.ailabs.devops.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.config.DevopsProperties.Health;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;

/** The systems the agent can reach. Stored in {@code systems.yml} and edited in the admin UI. */
public record Systems(List<JenkinsServer> jenkins, List<DockerRegistry> dockerhub,
                      List<KubeCluster> kubernetes, Health health) {

    public Systems {
        jenkins = jenkins != null ? List.copyOf(jenkins) : List.of();
        dockerhub = dockerhub != null ? List.copyOf(dockerhub) : List.of();
        kubernetes = kubernetes != null ? List.copyOf(kubernetes) : List.of();
        health = health != null ? health : new Health(null);
    }

    public static Systems empty() {
        return new Systems(null, null, null, null);
    }

    /** Human-readable problems; empty when the definition is usable. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JenkinsServer j : jenkins) {
            if (check(errors, seen, "jenkins", j.name(), "url", j.url(), "username", j.username(), "token-ref", j.tokenRef())) {
                url(errors, "jenkins", j.name(), j.url());
            }
        }
        seen.clear();
        for (KubeCluster k : kubernetes) {
            if (check(errors, seen, "kubernetes", k.name(), "api-server", k.apiServer(), "token-ref", k.tokenRef())) {
                url(errors, "kubernetes", k.name(), k.apiServer());
            }
            for (String ns : k.allowedNamespaces()) {
                if (!ns.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")) {
                    errors.add("kubernetes '" + k.name() + "': '" + ns + "' is not a valid namespace");
                }
            }
        }
        seen.clear();
        for (DockerRegistry d : dockerhub) {
            check(errors, seen, "dockerhub", d.name());
        }
        return errors;
    }

    private static boolean check(List<String> errors, Set<String> seen, String kind, String name, String... fields) {
        if (name == null || name.isBlank()) {
            errors.add(kind + ": every entry needs a name");
            return false;
        }
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
            errors.add(kind + " '" + name + "': name may only contain letters, digits, . _ -");
        }
        if (!seen.add(name)) {
            errors.add(kind + " '" + name + "' is defined twice");
        }
        boolean complete = true;
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] == null || fields[i + 1].isBlank()) {
                errors.add(kind + " '" + name + "' is missing " + fields[i]);
                complete = false;
            }
        }
        return complete;
    }

    private static void url(List<String> errors, String kind, String name, String url) {
        try {
            URI u = URI.create(url);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null) {
                errors.add(kind + " '" + name + "': URL must be http(s)://host[:port]");
            }
        } catch (IllegalArgumentException e) {
            errors.add(kind + " '" + name + "': invalid URL " + url);
        }
    }
}
