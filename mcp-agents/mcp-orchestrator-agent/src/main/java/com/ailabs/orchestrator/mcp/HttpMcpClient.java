package com.ailabs.orchestrator.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP over streamable HTTP: every message is a POST; the reply is either {@code application/json} or an SSE
 * stream carrying the response. Keeps the {@code Mcp-Session-Id} and re-initializes once if the server forgot it.
 */
public final class HttpMcpClient extends McpClient {

    private final URI uri;
    private final Supplier<String> token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile String sessionId;

    /** @param token supplies the bearer token on each request (may return null) */
    public HttpMcpClient(URI uri, Supplier<String> token) {
        this.uri = uri;
        this.token = token;
    }

    @Override
    protected void resetSession() {
        sessionId = null;
    }

    @Override
    public boolean alive() {
        return true;
    }

    private HttpRequest.Builder base(Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        String t = token.get();
        if (t != null && !t.isBlank()) b.header("Authorization", "Bearer " + t);
        String s = sessionId;
        if (s != null) b.header("Mcp-Session-Id", s);
        McpServerInfo i = info;
        if (i != null) b.header("MCP-Protocol-Version", i.protocolVersion());
        return b;
    }

    @Override
    protected JsonNode send(ObjectNode request, Duration timeout) {
        return send(request, timeout, true);
    }

    private JsonNode send(ObjectNode request, Duration timeout, boolean mayRetry) {
        String method = request.path("method").asText();
        HttpResponse<InputStream> res;
        try {
            res = http.send(base(timeout).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpException("cannot reach " + uri + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        }
        res.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
        int st = res.statusCode();
        try (InputStream in = res.body()) {
            if (st == 404 && sessionId != null && mayRetry && !"initialize".equals(method)) {
                initialize();                       // session expired (server restarted): start a new one
                return send(request, timeout, false);
            }
            if (st == 401 || st == 403) throw new McpException("the agent rejected the token (HTTP " + st + ")");
            if (st >= 400) throw new McpException("HTTP " + st + " from " + uri + ": " + snippet(in));
            String ct = res.headers().firstValue("Content-Type").orElse("");
            long id = request.path("id").asLong();
            if (ct.startsWith("text/event-stream")) return readSse(in, id);
            JsonNode body = JSON.readTree(in);
            if (body != null && body.isArray()) {
                for (JsonNode n : body) if (n.path("id").asLong(-1) == id) return n;
            }
            if (body == null || body.isMissingNode()) throw new McpException("empty response to " + method);
            return body;
        } catch (IOException e) {
            throw new McpException("bad response from " + uri + ": " + describe(e), e);
        }
    }

    static JsonNode readSse(InputStream in, long id) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while (true) {
            line = br.readLine();
            if (line == null || line.isEmpty()) {
                if (!data.isEmpty()) {
                    JsonNode n = JSON.readTree(data.toString());
                    data.setLength(0);
                    if (n.path("id").asLong(-1) == id && (n.has("result") || n.has("error"))) return n;
                }
                if (line == null) break;
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        throw new McpException("the event stream ended without a response");
    }

    @Override
    protected void notify(ObjectNode notification) {
        try {
            HttpResponse<Void> r = http.send(base(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(notification))).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 400 && r.statusCode() != 405) {
                throw new McpException("notification rejected with HTTP " + r.statusCode());
            }
        } catch (IOException e) {
            throw new McpException("cannot reach " + uri + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        String s = sessionId;
        if (s == null) return;
        try {
            http.send(base(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // best effort
        }
        sessionId = null;
    }

    private static String snippet(InputStream in) throws IOException {
        String s = new String(in.readNBytes(400), StandardCharsets.UTF_8).strip();
        return s.isEmpty() ? "(no body)" : s;
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
