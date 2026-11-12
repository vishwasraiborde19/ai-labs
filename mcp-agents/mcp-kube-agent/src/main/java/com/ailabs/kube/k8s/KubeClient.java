package com.ailabs.kube.k8s;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.ailabs.kube.config.KubeProperties.Cluster;
import com.ailabs.kube.http.HttpSupport;
import com.ailabs.kube.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Kubernetes REST API of one cluster, authenticated with a bearer (service account) token.
 * Generic on purpose: the tools work on any resource type the cluster serves, CRDs included.
 */
public class KubeClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CATALOG_TTL = Duration.ofMinutes(10);

    private final Cluster cfg;
    private final Supplier<String> token;
    private final HttpClient http;
    private Catalog catalog;
    private Instant catalogExpires = Instant.EPOCH;

    public KubeClient(Cluster cfg, Supplier<String> token, String caPem) {
        if (cfg.apiServer() == null || cfg.apiServer().isBlank()) {
            throw new IllegalStateException("Cluster '" + cfg.name() + "' has no api-server"
                    + (cfg.inCluster() ? " (in-cluster, but KUBERNETES_SERVICE_HOST is not set)" : ""));
        }
        this.cfg = cfg;
        this.token = token;
        this.http = HttpSupport.client(cfg.insecureSkipTlsVerify(), caPem, false, Duration.ofSeconds(10));
    }

    public String name() {
        return cfg.name();
    }

    public Cluster config() {
        return cfg;
    }

    /** Resource types of this cluster, rediscovered every 10 minutes (or on {@code refresh}). */
    public synchronized Catalog catalog(boolean refresh) {
        if (refresh || catalog == null || Instant.now().isAfter(catalogExpires)) {
            catalog = Catalog.discover(this);
            catalogExpires = Instant.now().plus(CATALOG_TTL);
        }
        return catalog;
    }

    public Catalog catalog() {
        return catalog(false);
    }

    public JsonNode get(String path) {
        return call("GET", path, null, null);
    }

    /** GET that returns null on 404 instead of failing. */
    public JsonNode getOrNull(String path) {
        Resp r = send("GET", path, null, null);
        if (r.status() == 404) {
            return null;
        }
        if (!r.ok()) {
            throw failure("GET", path, r);
        }
        return r.json();
    }

    public JsonNode post(String path, String json) {
        return call("POST", path, "application/json", json);
    }

    public JsonNode patch(String path, String contentType, String body) {
        return call("PATCH", path, contentType, body);
    }

    public JsonNode delete(String path, String json) {
        return call("DELETE", path, "application/json", json);
    }

    /** Plain-text endpoints such as pod logs. */
    public String text(String path) {
        Resp r = send("GET", path, null, null);
        if (!r.ok()) {
            throw failure("GET", path, r);
        }
        return r.body();
    }

    public JsonNode call(String method, String path, String contentType, String body) {
        Resp r = send(method, path, contentType, body);
        if (!r.ok()) {
            throw failure(method, path, r);
        }
        return r.json();
    }

    public Resp send(String method, String path, String contentType, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(HttpSupport.stripSlash(cfg.apiServer()) + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json, */*")
                .header("Authorization", "Bearer " + token.get());
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return HttpSupport.send(http, b.build());
    }

    /** Uses the API's own Status message ("deployments.apps \"x\" not found", "forbidden: ...") when there is one. */
    KubeException failure(String method, String path, Resp r) {
        String msg = r.body();
        try {
            JsonNode s = HttpSupport.JSON.readTree(msg);
            if (s != null && s.hasNonNull("message")) {
                msg = s.path("message").asText();
            }
        } catch (Exception ignored) {
            // not JSON
        }
        String hint = switch (r.status()) {
            case 401 -> " (token rejected: expired, revoked or for another cluster)";
            case 403 -> " (the service account's RBAC does not allow this; see k8s_can_i)";
            default -> "";
        };
        return new KubeException(name() + ": " + method + " " + path.replaceAll("\\?.*", "") + " -> HTTP "
                + r.status() + ": " + HttpSupport.truncate(msg, 800) + hint, r.status());
    }
}
