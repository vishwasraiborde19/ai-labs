package com.ailabs.devops.docker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Docker Hub's web API (hub.docker.com/v2), used to confirm that a pipeline really pushed an image
 * and to look up tags. Images are built and pushed by Jenkins, not by this agent.
 */
public class DockerHubClient {

    private static final String HUB = "https://hub.docker.com";

    private final DockerRegistry cfg;
    private final Supplier<String> password;
    private final HttpClient http = HttpSupport.client(false, null, false, Duration.ofSeconds(10));
    private volatile String jwt;
    private volatile Instant jwtExpires = Instant.EPOCH;

    public DockerHubClient(DockerRegistry cfg, Supplier<String> password) {
        this.cfg = cfg;
        this.password = password;
    }

    /** {@code app} -> {@code <namespace>/app}; {@code nginx} without namespace -> {@code library/nginx}. */
    public String qualify(String repository) {
        String repo = repository.startsWith("docker.io/") ? repository.substring("docker.io/".length()) : repository;
        if (repo.contains(":")) {
            repo = repo.substring(0, repo.indexOf(':'));
        }
        if (repo.contains("/")) {
            return repo;
        }
        String ns = cfg != null && cfg.namespace() != null && !cfg.namespace().isBlank() ? cfg.namespace() : "library";
        return ns + "/" + repo;
    }

    public JsonNode login() {
        authHeader();
        return HttpSupport.JSON.createObjectNode().put("authenticated", jwt != null)
                .put("username", cfg != null ? String.valueOf(cfg.username()) : "anonymous");
    }

    public Map<String, Object> repository(String repository) {
        String repo = qualify(repository);
        JsonNode r = get("/v2/repositories/" + repo + "/", "Repository " + repo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repository", repo);
        m.put("private", r.path("is_private").asBoolean());
        m.put("lastUpdated", r.path("last_updated").asText());
        m.put("pullCount", r.path("pull_count").asLong());
        m.put("description", r.path("description").asText());
        return m;
    }

    public List<Map<String, Object>> tags(String repository, int pageSize) {
        String repo = qualify(repository);
        JsonNode r = get("/v2/repositories/" + repo + "/tags?ordering=last_updated&page_size="
                + Math.min(Math.max(pageSize, 1), 100), "Tags of " + repo);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : r.path("results")) {
            out.add(tag(t));
        }
        return out;
    }

    /** Returns the tag, or null if it does not exist. */
    public Map<String, Object> findTag(String repository, String tag) {
        String repo = qualify(repository);
        Resp r = send("/v2/repositories/" + repo + "/tags/" + HttpSupport.enc(tag) + "/");
        if (r.status() == 404) {
            return null;
        }
        if (!r.ok()) {
            throw HttpSupport.failure("Tag " + repo + ":" + tag, r);
        }
        Map<String, Object> m = tag(r.json());
        m.put("repository", repo);
        return m;
    }

    private static Map<String, Object> tag(JsonNode t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", t.path("name").asText());
        m.put("lastUpdated", t.path("last_updated").asText());
        m.put("digest", t.path("digest").asText(null));
        m.put("sizeBytes", t.path("full_size").asLong());
        List<String> platforms = new ArrayList<>();
        t.path("images").forEach(i -> platforms.add(i.path("os").asText() + "/" + i.path("architecture").asText()));
        m.put("platforms", platforms);
        return m;
    }

    private JsonNode get(String path, String what) {
        Resp r = send(path);
        if (r.status() == 404) {
            throw new IllegalStateException(what + " not found on Docker Hub (private repos need a configured registry with credentials)");
        }
        if (!r.ok()) {
            throw HttpSupport.failure(what, r);
        }
        return r.json();
    }

    private Resp send(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(HUB + path)).timeout(Duration.ofSeconds(20)).GET();
        String auth = authHeader();
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return HttpSupport.send(http, b.build());
    }

    /** Exchange username + password/PAT for a short-lived JWT; anonymous when no password is configured. */
    private synchronized String authHeader() {
        if (cfg == null || cfg.username() == null || cfg.username().isBlank()) {
            return null;
        }
        if (jwt != null && Instant.now().isBefore(jwtExpires)) {
            return "Bearer " + jwt;
        }
        String pw = password.get();
        if (pw == null) {
            return null;
        }
        String body = HttpSupport.JSON.createObjectNode().put("username", cfg.username()).put("password", pw).toString();
        Resp r = HttpSupport.send(http, HttpRequest.newBuilder(URI.create(HUB + "/v2/users/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build());
        if (!r.ok()) {
            throw new IllegalStateException("Docker Hub login as " + cfg.username() + " failed: HTTP " + r.status());
        }
        jwt = r.json().path("token").asText(null);
        jwtExpires = Instant.now().plus(Duration.ofMinutes(20));
        return jwt == null ? null : "Bearer " + jwt;
    }
}
