package com.ailabs.devops.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ailabs.devops.config.ConfigStore;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;

/** HTTP health probe with retries, used on its own and as the last step of a release check. */
@Component
public class HealthChecker {

    private final ConfigStore store;
    private final HttpClient secure = HttpSupport.client(false, null, false, Duration.ofSeconds(5));
    private final HttpClient insecure = HttpSupport.client(true, null, false, Duration.ofSeconds(5));

    public HealthChecker(ConfigStore store) {
        this.store = store;
    }

    public Map<String, Object> check(String url, Integer expectedStatus, String bodyContains, Integer attempts,
                                     Integer intervalSeconds, Integer timeoutSeconds, Boolean insecureTls) {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Only http and https URLs can be health-checked");
        }
        requireAllowed(uri.getHost());
        int expect = expectedStatus != null ? expectedStatus : 200;
        int tries = Math.min(Math.max(attempts != null ? attempts : 1, 1), 60);
        int interval = Math.min(Math.max(intervalSeconds != null ? intervalSeconds : 5, 1), 120);
        int timeout = Math.min(Math.max(timeoutSeconds != null ? timeoutSeconds : 5, 1), 60);
        HttpClient client = Boolean.TRUE.equals(insecureTls) ? insecure : secure;

        List<Map<String, Object>> log = new ArrayList<>();
        boolean healthy = false;
        for (int i = 1; i <= tries && !healthy; i++) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("attempt", i);
            long t0 = System.nanoTime();
            try {
                Resp r = HttpSupport.send(client, HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(timeout)).header("User-Agent", "mcp-devops-agent").GET().build());
                a.put("status", r.status());
                a.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
                boolean bodyOk = bodyContains == null || bodyContains.isBlank()
                        || (r.body() != null && r.body().contains(bodyContains));
                healthy = r.status() == expect && bodyOk;
                if (!healthy) {
                    a.put("body", HttpSupport.truncate(r.body(), 300));
                }
            } catch (RuntimeException e) {
                a.put("error", e.getMessage());
            }
            log.add(a);
            if (!healthy && i < tries) {
                sleep(interval * 1000L);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("healthy", healthy);
        out.put("expectedStatus", expect);
        if (bodyContains != null && !bodyContains.isBlank()) {
            out.put("bodyContains", bodyContains);
        }
        out.put("attempts", log);
        return out;
    }

    void requireAllowed(String host) {
        List<String> allowedHosts = store.current().health().allowedHosts();
        if (allowedHosts.isEmpty()) {
            return;
        }
        for (String pattern : allowedHosts) {
            if (pattern.equalsIgnoreCase(host)
                    || (pattern.startsWith("*.") && host != null && host.toLowerCase().endsWith(pattern.substring(1).toLowerCase()))) {
                return;
            }
        }
        throw new IllegalArgumentException("Host " + host + " is not in devops.health.allowed-hosts " + allowedHosts);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
