package com.ailabs.devops.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;

/**
 * Checks that a system definition works: secrets resolve, the endpoint answers, the credentials
 * are accepted. Used by the {@code devops_check_access} tool and by the admin UI (where the
 * definition may not be saved yet). Never reports secret values.
 */
@Component
public class ConnectionTester {

    private final Resources resources;

    public ConnectionTester(Resources resources) {
        this.resources = resources;
    }

    public Map<String, Object> jenkins(JenkinsServer j) {
        return probe("jenkins", j.name(), () -> {
            var c = resources.newJenkins(j);
            var who = c.whoAmI();
            String user = who.path("name").asText();
            if ("anonymous".equals(user)) {
                throw new IllegalStateException("Jenkins treated the request as anonymous; check username and API token");
            }
            return "authenticated as " + user + ", Jenkins " + c.version().path("version").asText();
        });
    }

    public Map<String, Object> dockerhub(DockerRegistry d) {
        return probe("dockerhub", d.name(), () ->
                resources.newDocker(d).login().path("authenticated").asBoolean() ? "logged in as " + d.username() : "anonymous (no credentials)");
    }

    public Map<String, Object> kubernetes(KubeCluster k) {
        return probe("kubernetes", k.name(), () -> {
            var c = resources.newKube(k);
            String v = c.version().path("gitVersion").asText();
            if (!k.allowedNamespaces().isEmpty()) {
                String ns = k.allowedNamespaces().get(0);
                int n = c.listDeployments(ns).size();
                return "Kubernetes " + v + ", " + n + " deployments visible in " + ns;
            }
            return "Kubernetes " + v;
        });
    }

    static Map<String, Object> probe(String kind, String name, Supplier<String> test) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        long t0 = System.currentTimeMillis();
        try {
            m.put("detail", test.get());
            m.put("ok", true);
        } catch (RuntimeException e) {
            m.put("detail", GuardedToolCallback.firstMessage(e));
            m.put("ok", false);
        }
        m.put("ms", System.currentTimeMillis() - t0);
        return m;
    }
}
