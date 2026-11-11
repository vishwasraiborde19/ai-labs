package com.ailabs.vmadmin.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.ailabs.vmadmin.fleet.Host;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Talks to one mcp-vm-worker-agent over its REST API ({@code /api/*}, token scope {@code rest}) and,
 * for self-updates only, its MCP endpoint ({@code /mcp}, scope {@code mcp}).
 */
public class AgentClient {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Host host;
    private final Supplier<String> token;
    private final HttpClient http;

    public AgentClient(Host host, Supplier<String> token) {
        this.host = host;
        this.token = token;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)  // uvicorn speaks HTTP/1.1; skip the h2c upgrade attempt
                .followRedirects(HttpClient.Redirect.NEVER);
        if (host.insecureSkipTlsVerify()) {
            b.sslContext(trustAll());
        } else if (host.caCertRef() != null) {
            b.sslContext(trusting(host.caCertRef().substring("path:".length())));
        }
        this.http = b.build();
    }

    public Host host() {
        return host;
    }

    // ------------------------------------------------------------------ info

    public JsonNode health(Duration timeout) {
        return json(send(req("/health", timeout).GET(), false));
    }

    public JsonNode info(Duration timeout) {
        return json(send(req("/api/info", timeout).GET(), true));
    }

    // ------------------------------------------------------------------ commands and jobs

    public JsonNode run(String command, String cwd, int timeoutSeconds) {
        ObjectNode b = JSON.createObjectNode().put("command", command).put("timeout_seconds", timeoutSeconds);
        if (cwd != null && !cwd.isBlank()) {
            b.put("cwd", cwd);
        }
        return json(send(post("/api/run", b, Duration.ofSeconds(timeoutSeconds + 15L)), true));
    }

    public JsonNode jobs() {
        return json(send(req("/api/jobs", DEFAULT_TIMEOUT).GET(), true));
    }

    public JsonNode startJob(String command, String cwd) {
        ObjectNode b = JSON.createObjectNode().put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            b.put("cwd", cwd);
        }
        return json(send(post("/api/jobs", b, DEFAULT_TIMEOUT), true));
    }

    public JsonNode job(String id, int tail) {
        return json(send(req("/api/jobs/" + enc(id) + "?tail=" + tail, DEFAULT_TIMEOUT).GET(), true));
    }

    public JsonNode killJob(String id) {
        return json(send(req("/api/jobs/" + enc(id), DEFAULT_TIMEOUT).DELETE(), true));
    }

    // ------------------------------------------------------------------ files (agent 2.1.0+)

    public JsonNode list(String path, boolean hidden) {
        return json(send(req("/api/fs/list?" + q(Map.of("path", nz(path), "hidden", hidden ? "1" : "0")), DEFAULT_TIMEOUT).GET(), true));
    }

    public JsonNode read(String path, long maxBytes) {
        return json(send(req("/api/fs/read?" + q(Map.of("path", path, "max_bytes", String.valueOf(maxBytes))), DEFAULT_TIMEOUT).GET(), true));
    }

    public JsonNode stat(String path) {
        return json(send(req("/api/fs/stat?" + q(Map.of("path", path)), DEFAULT_TIMEOUT).GET(), true));
    }

    /** Streams a file; the caller must close the body. */
    public HttpResponse<InputStream> download(String path) {
        HttpRequest r = authorize(req("/api/fs/download?" + q(Map.of("path", path)), Duration.ofMinutes(30)).GET()).build();
        HttpResponse<InputStream> res = exchange(r, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            try (InputStream in = res.body()) {
                throw failure(res.statusCode(), new String(in.readNBytes(4000), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw failure(res.statusCode(), "");
            }
        }
        return res;
    }

    public JsonNode write(String path, Supplier<InputStream> body, long length, boolean backup, boolean overwrite, boolean mkdirs) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("path", path);
        p.put("backup", backup ? "1" : "0");
        p.put("overwrite", overwrite ? "1" : "0");
        p.put("mkdirs", mkdirs ? "1" : "0");
        HttpRequest.BodyPublisher pub = length == 0 ? HttpRequest.BodyPublishers.noBody()
                : length > 0 ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(body), length)
                : HttpRequest.BodyPublishers.ofInputStream(body);
        return json(send(req("/api/fs/file?" + q(p), Duration.ofMinutes(30))
                .header("Content-Type", "application/octet-stream").PUT(pub), true));
    }

    public JsonNode writeText(String path, String content, boolean backup) {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        return write(path, () -> new ByteArrayInputStream(b), b.length, backup, true, false);
    }

    public JsonNode mkdir(String path) {
        return json(send(post("/api/fs/mkdir", JSON.createObjectNode().put("path", path), DEFAULT_TIMEOUT), true));
    }

    public JsonNode rename(String from, String to, boolean overwrite) {
        return json(send(post("/api/fs/rename", JSON.createObjectNode().put("from", from).put("to", to)
                .put("overwrite", overwrite), DEFAULT_TIMEOUT), true));
    }

    public JsonNode delete(String path, boolean recursive) {
        return json(send(req("/api/fs?" + q(Map.of("path", path, "recursive", recursive ? "1" : "0")),
                Duration.ofMinutes(5)).DELETE(), true));
    }

    // ------------------------------------------------------------------ terminal sessions (agent 2.2.0+)

    public JsonNode ptyOpen(String cwd, int cols, int rows) {
        ObjectNode b = JSON.createObjectNode().put("cols", cols).put("rows", rows);
        if (cwd != null && !cwd.isBlank()) {
            b.put("cwd", cwd);
        }
        return json(send(post("/api/pty", b, DEFAULT_TIMEOUT), true));
    }

    /** Long-poll: waits up to {@code waitSeconds} for output past {@code offset}. */
    public JsonNode ptyOutput(String id, long offset, int waitSeconds) {
        return json(send(req("/api/pty/" + enc(id) + "/output?offset=" + offset + "&wait=" + waitSeconds,
                Duration.ofSeconds(waitSeconds + 15L)).GET(), true));
    }

    public JsonNode ptyInput(String id, String base64) {
        return json(send(post("/api/pty/" + enc(id) + "/input", JSON.createObjectNode().put("b64", base64), DEFAULT_TIMEOUT), true));
    }

    public JsonNode ptyResize(String id, int cols, int rows) {
        return json(send(post("/api/pty/" + enc(id) + "/resize", JSON.createObjectNode().put("cols", cols).put("rows", rows), DEFAULT_TIMEOUT), true));
    }

    public JsonNode ptyClose(String id) {
        return json(send(req("/api/pty/" + enc(id), DEFAULT_TIMEOUT).DELETE(), true));
    }

    // ------------------------------------------------------------------ MCP (self-update)

    /** One stateless MCP {@code tools/call}; the agent runs FastMCP with stateless_http + json_response. */
    public JsonNode mcpTool(String tool, Map<String, Object> args, Duration timeout) {
        ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        body.putObject("params").put("name", tool).set("arguments", JSON.valueToTree(args));
        HttpRequest.Builder r = req("/mcp", timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> res = exchange(authorize(r).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) {
            throw new AgentException(403, "The token for " + host.name() + " lacks the 'mcp' scope needed for upgrades");
        }
        if (res.statusCode() / 100 != 2) {
            throw failure(res.statusCode(), res.body());
        }
        String text = res.body().trim();
        if (text.startsWith("event:") || text.startsWith("data:")) {  // SSE framing from older SDKs
            text = text.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).reduce("", (a, b) -> b);
        }
        JsonNode rpc = parse(text);
        if (rpc.has("error")) {
            throw new AgentException(502, host.name() + " MCP error: " + rpc.path("error").path("message").asText());
        }
        JsonNode result = rpc.path("result");
        JsonNode structured = result.path("structuredContent");
        JsonNode out = structured.isMissingNode() ? parse(result.path("content").path(0).path("text").asText("{}"))
                : structured.has("result") && structured.size() == 1 ? structured.get("result") : structured;
        if (result.path("isError").asBoolean(false)) {
            throw new AgentException(502, host.name() + ": " + result.path("content").path(0).path("text").asText("tool failed"));
        }
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    private HttpRequest.Builder req(String pathAndQuery, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(host.url() + pathAndQuery)).timeout(timeout);
    }

    private HttpRequest.Builder post(String path, JsonNode body, Duration timeout) {
        return req(path, timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private HttpRequest.Builder authorize(HttpRequest.Builder b) {
        return b.header("Authorization", "Bearer " + token.get());
    }

    private HttpResponse<String> send(HttpRequest.Builder b, boolean auth) {
        HttpResponse<String> res = exchange((auth ? authorize(b) : b).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw failure(res.statusCode(), res.body());
        }
        return res;
    }

    private <T> HttpResponse<T> exchange(HttpRequest r, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(r, handler);
        } catch (HttpTimeoutException e) {
            throw new AgentException(504, host.name() + " did not answer in time", e);
        } catch (ConnectException e) {
            throw new AgentException(502, host.name() + " is unreachable (connection refused or no route)", e);
        } catch (SSLException e) {
            throw new AgentException(502, host.name() + " TLS error: " + e.getMessage()
                    + "; set its CA certificate or, for labs, skip TLS verification", e);
        } catch (IOException e) {
            throw new AgentException(502, host.name() + " request failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(503, "interrupted", e);
        }
    }

    private AgentException failure(int status, String body) {
        String msg = body;
        try {
            JsonNode n = JSON.readTree(body);
            if (n.hasNonNull("error")) {
                msg = n.get("error").asText();
            }
        } catch (IOException | RuntimeException ignored) {
            // not JSON
        }
        msg = msg == null ? "" : msg.length() > 400 ? msg.substring(0, 400) + "..." : msg;
        return switch (status) {
            case 401 -> new AgentException(502, host.name() + " rejected the token (401); it may be revoked, expired or lack the 'rest' scope");
            case 404 -> msg.toLowerCase().contains("no such") || msg.contains("Not a") || msg.contains("unknown")
                    ? new AgentException(404, msg)
                    : new AgentException(501, host.name() + " does not support this (agent too old?); use Upgrade agent");
            case 405 -> new AgentException(501, host.name() + " does not support this (agent too old?); use Upgrade agent");
            default -> new AgentException(status >= 400 && status < 500 ? status : 502,
                    status >= 500 ? host.name() + " failed (HTTP " + status + "): " + msg : msg);
        };
    }

    private JsonNode json(HttpResponse<String> r) {
        return parse(r.body());
    }

    private JsonNode parse(String s) {
        try {
            return JSON.readTree(s == null || s.isBlank() ? "{}" : s);
        } catch (IOException e) {
            throw new AgentException(502, host.name() + " returned something that is not JSON");
        }
    }

    static String q(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(k).append('=').append(enc(v));
        });
        return sb.toString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static SSLContext trusting(String pemFile) {
        try {
            byte[] pem = Files.readAllBytes(Path.of(pemFile));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load CA certificate " + pemFile + ": " + e.getMessage(), e);
        }
    }

    static SSLContext trustAll() {
        try {
            TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
