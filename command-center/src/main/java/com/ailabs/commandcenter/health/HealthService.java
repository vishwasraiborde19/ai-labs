package com.ailabs.commandcenter.health;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ailabs.commandcenter.catalog.CatalogStore;
import com.ailabs.commandcenter.catalog.KindRegistry;
import com.ailabs.commandcenter.catalog.Model.Link;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;
import com.ailabs.commandcenter.config.CcProperties;

/**
 * Probes every system over HTTP(S) in the background and keeps the last results in memory.
 *
 * <p>Reachability, not correctness: any answer that shows the service is there counts as up,
 * including 401/403 (login required) and 404/405/406 on API or MCP endpoints that don't answer a
 * bare GET. 5xx, timeouts and refused connections are down. TLS certificate expiry is recorded.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int HISTORY = 120;

    /**
     * @param state       up, down, unknown (nothing to probe)
     * @param certDaysLeft days until the TLS certificate expires, null for http
     */
    public record Result(String id, String state, String url, Integer httpStatus, Long latencyMs, String message,
                         Integer certDaysLeft, Instant checkedAt) {}

    public record Point(long t, String state, Long latencyMs) {}

    private final CatalogStore store;
    private final KindRegistry kinds;
    private final CcProperties.Health cfg;
    private final HttpClient strict;
    private final HttpClient lenient;
    private final Map<String, Result> latest = new ConcurrentHashMap<>();
    private final Map<String, Deque<Point>> history = new ConcurrentHashMap<>();

    public HealthService(CatalogStore store, KindRegistry kinds, CcProperties props) {
        this.store = store;
        this.kinds = kinds;
        this.cfg = props.health();
        this.strict = HttpClient.newBuilder().connectTimeout(cfg.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        this.lenient = HttpClient.newBuilder().connectTimeout(cfg.timeout()).followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(trustAnything()).build();
    }

    @Scheduled(initialDelay = 2_000, fixedDelayString = "${commandcenter.health.interval:60s}")
    public void scheduled() {
        try {
            checkAll();
        } catch (RuntimeException e) {
            log.warn("Health round failed: {}", e.getMessage());
        }
    }

    /** Probes every system in parallel and returns the results. */
    public Map<String, Result> checkAll() {
        List<SystemEntry> systems = store.snapshot().catalog().systems();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> fs = new ArrayList<>();
            for (SystemEntry s : systems) {
                fs.add(ex.submit(() -> check(s)));
            }
            for (Future<Result> f : fs) {
                try {
                    f.get(cfg.timeout().toMillis() * 2 + 2_000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // recorded inside check(); a stuck probe just keeps its previous result
                }
            }
        }
        latest.keySet().retainAll(systems.stream().map(SystemEntry::id).toList());
        history.keySet().retainAll(latest.keySet());
        return results();
    }

    public Result check(String id) {
        return check(store.require(id));
    }

    public Result check(SystemEntry s) {
        String url = target(s);
        Result r;
        if (url == null) {
            r = new Result(s.id(), "unknown", null, null, null, "No URL to probe: add a link or a health URL", null, Instant.now());
        } else {
            r = probe(s.id(), url, s.tlsInsecure());
        }
        latest.put(s.id(), r);
        Deque<Point> h = history.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(new Point(r.checkedAt().toEpochMilli(), r.state(), r.latencyMs()));
            while (h.size() > HISTORY) {
                h.removeFirst();
            }
        }
        return r;
    }

    public Map<String, Result> results() {
        return Map.copyOf(latest);
    }

    /** Latest result plus uptime over the kept history, per system. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Result> e : latest.entrySet()) {
            Deque<Point> h = history.getOrDefault(e.getKey(), new ArrayDeque<>());
            List<Point> pts;
            synchronized (h) {
                pts = List.copyOf(h);
            }
            long known = pts.stream().filter(p -> !"unknown".equals(p.state())).count();
            long up = pts.stream().filter(p -> "up".equals(p.state())).count();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("latest", e.getValue());
            v.put("uptimePct", known == 0 ? null : Math.round(1000.0 * up / known) / 10.0);
            v.put("samples", pts.size());
            v.put("history", pts.subList(Math.max(0, pts.size() - 40), pts.size()));
            v.put("certWarn", e.getValue().certDaysLeft() != null && e.getValue().certDaysLeft() < cfg.certWarnDays());
            m.put(e.getKey(), v);
        }
        return m;
    }

    public CcProperties.Health config() {
        return cfg;
    }

    /** health URL, else a health link, else the kind's health path on the first link, else ui/api/mcp link. */
    String target(SystemEntry s) {
        if (s.healthUrl() != null) {
            return s.healthUrl();
        }
        List<Link> links = s.links();
        for (String type : List.of("health", "ui", "api", "mcp", "metrics")) {
            for (Link l : links) {
                if (type.equals(l.type())) {
                    if (!"health".equals(type)) {
                        String hp = kinds.kind(s.kind()).healthPath();
                        if (hp != null && !hp.isBlank() && links.indexOf(l) == 0) {
                            return origin(l.url()) + hp;
                        }
                    }
                    return l.url();
                }
            }
        }
        return links.isEmpty() ? null : links.get(0).url();
    }

    private static String origin(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    private Result probe(String id, String url, boolean insecure) {
        long t0 = System.nanoTime();
        Instant now = Instant.now();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(cfg.timeout())
                    .header("User-Agent", "ai-labs-command-center").GET().build();
            HttpResponse<Void> r = (insecure ? lenient : strict).send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Integer days = r.sslSession().map(ss -> {
                try {
                    Certificate[] chain = ss.getPeerCertificates();
                    if (chain.length > 0 && chain[0] instanceof X509Certificate x) {
                        return (int) Duration.between(now, x.getNotAfter().toInstant()).toDays();
                    }
                } catch (Exception ignored) {
                    // no peer certificate
                }
                return null;
            }).orElse(null);
            int sc = r.statusCode();
            String state = sc >= 500 ? "down" : "up";
            String msg = switch (sc / 100) {
                case 2 -> "OK";
                case 3 -> "Redirect (" + r.headers().firstValue("location").orElse("?") + ")";
                case 4 -> sc == 401 || sc == 403 ? "Reachable, login required" : "Reachable (HTTP " + sc + " on a plain GET)";
                default -> "Server error";
            };
            return new Result(id, state, url, sc, ms, msg, days, now);
        } catch (HttpConnectTimeoutException e) {
            return down(id, url, t0, now, "Connection timed out");
        } catch (HttpTimeoutException e) {
            return down(id, url, t0, now, "No answer within " + cfg.timeout().toSeconds() + " s");
        } catch (SSLHandshakeException e) {
            return down(id, url, t0, now, "TLS failed (" + e.getMessage() + "); tick 'self-signed certificate' if this system uses one");
        } catch (ConnectException e) {
            return down(id, url, t0, now, "Connection refused or host unreachable");
        } catch (IOException e) {
            return down(id, url, t0, now, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(id, url, t0, now, "Interrupted");
        } catch (IllegalArgumentException e) {
            return new Result(id, "unknown", url, null, null, "Invalid URL: " + e.getMessage(), null, now);
        }
    }

    private static Result down(String id, String url, long t0, Instant now, String msg) {
        return new Result(id, "down", url, null, (System.nanoTime() - t0) / 1_000_000, msg, null, now);
    }

    /** For systems marked insecureTls: any certificate, any host name. */
    static SSLContext trustAnything() {
        TrustManager tm = new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
