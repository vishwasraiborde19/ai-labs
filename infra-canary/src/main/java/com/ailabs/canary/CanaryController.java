package com.ailabs.canary;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /canary: one call that says whether the platform around this pod works.
 * 200 when every check passes, 503 otherwise; the body lists each check.
 *
 *   log-volume  write, read back and delete a file next to the log file (Longhorn volume mounted, writable)
 *   dns         resolve canary.dns-name (cluster DNS / CoreDNS)
 *   http:NAME   GET each canary.http-targets entry (NAME=URL, comma separated), expect 2xx
 */
@RestController
public class CanaryController {
    private static final Logger log = LoggerFactory.getLogger(CanaryController.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Instant started = Instant.now();
    private final String version;

    @Value("${logging.file.name:}") String logFile;
    @Value("${canary.dns-name:kubernetes.default.svc.cluster.local}") String dnsName;
    @Value("${canary.http-targets:}") String httpTargets;

    public CanaryController(ObjectProvider<BuildProperties> build) {
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    @GetMapping("/canary")
    public ResponseEntity<Map<String, Object>> canary() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(logVolume());
        checks.add(dns());
        for (String t : httpTargets.split(",")) {
            int eq = t.indexOf('=');
            if (eq > 0) checks.add(httpGet(t.substring(0, eq).trim(), t.substring(eq + 1).trim()));
        }
        boolean ok = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "OK" : "FAIL");
        body.put("version", version);
        body.put("pod", env("HOSTNAME", "local"));
        body.put("node", env("NODE_NAME", "unknown"));
        body.put("namespace", env("POD_NAMESPACE", "unknown"));
        body.put("uptimeSeconds", Duration.between(started, Instant.now()).toSeconds());
        body.put("checks", checks);
        if (ok) log.info("canary OK ({} checks)", checks.size());
        else log.warn("canary FAIL: {}", checks.stream().filter(c -> !Boolean.TRUE.equals(c.get("ok"))).toList());
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    private Map<String, Object> logVolume() {
        if (logFile.isBlank()) return check("log-volume", true, "file logging off (logging.file.name not set): skipped");
        Path dir = Path.of(logFile).toAbsolutePath().getParent();
        Path probe = dir.resolve(".canary-probe");
        try {
            String token = Instant.now().toString();
            Files.writeString(probe, token);
            boolean same = token.equals(Files.readString(probe));
            Files.deleteIfExists(probe);
            long freeMb = Files.getFileStore(dir).getUsableSpace() / (1024 * 1024);
            long totalMb = Files.getFileStore(dir).getTotalSpace() / (1024 * 1024);
            return check("log-volume", same, dir + " writable, " + freeMb + " MiB free of " + totalMb);
        } catch (IOException e) {
            return check("log-volume", false, dir + ": " + e);
        }
    }

    private Map<String, Object> dns() {
        try {
            return check("dns", true, dnsName + " -> " + InetAddress.getByName(dnsName).getHostAddress());
        } catch (IOException e) {
            return check("dns", false, dnsName + ": " + e);
        }
    }

    private Map<String, Object> httpGet(String name, String url) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return check("http:" + name, r.statusCode() / 100 == 2, url + " -> " + r.statusCode() + " in " + ms + " ms");
        } catch (IOException | IllegalArgumentException e) {
            return check("http:" + name, false, url + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return check("http:" + name, false, url + ": interrupted");
        }
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    private static String env(String k, String def) {
        return System.getenv().getOrDefault(k, def);
    }
}
