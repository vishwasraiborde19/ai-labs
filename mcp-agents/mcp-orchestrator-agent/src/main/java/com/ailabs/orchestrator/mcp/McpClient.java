package com.ailabs.orchestrator.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal MCP client (JSON-RPC 2.0): initialize, tools/list, tools/call, ping. Transports are subclasses.
 * Written against the protocol rather than a framework client so agents can be added, changed and removed at
 * runtime without restarting the orchestrator.
 */
public abstract class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final Duration SHORT = Duration.ofSeconds(30);

    private final AtomicLong ids = new AtomicLong();
    protected volatile McpServerInfo info;

    /** Sends one request and returns the whole JSON-RPC response object. */
    protected abstract JsonNode send(ObjectNode request, Duration timeout);

    protected abstract void notify(ObjectNode notification);

    /** Called before a new initialize: forget any session. */
    protected void resetSession() {}

    public abstract boolean alive();

    @Override
    public abstract void close();

    public McpServerInfo info() {
        return info;
    }

    protected JsonNode request(String method, Object params, Duration timeout) {
        ObjectNode req = JSON.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.incrementAndGet());
        req.put("method", method);
        if (params != null) req.set("params", JSON.valueToTree(params));
        JsonNode resp = send(req, timeout);
        if (resp.hasNonNull("error")) {
            JsonNode e = resp.get("error");
            throw new McpException(method + " failed: " + e.path("message").asText(e.toString()));
        }
        return resp.path("result");
    }

    public synchronized McpServerInfo initialize() {
        resetSession();
        info = null;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "mcp-orchestrator-agent", "version", "0.1.0"));
        JsonNode r = request("initialize", params, SHORT);
        JsonNode si = r.path("serverInfo");
        McpServerInfo i = new McpServerInfo(si.path("name").asText(""), si.path("version").asText(""),
                r.path("protocolVersion").asText(PROTOCOL_VERSION), r.path("instructions").asText(""));
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", "notifications/initialized");
        notify(n);
        info = i;
        return i;
    }

    public List<McpTool> listTools() {
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            JsonNode r = request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), SHORT);
            for (JsonNode t : r.path("tools")) {
                Map<String, Object> schema = t.hasNonNull("inputSchema") ? JSON.convertValue(t.get("inputSchema"), MAP)
                        : Map.of("type", "object", "properties", Map.of());
                Map<String, Object> ann = t.hasNonNull("annotations") ? JSON.convertValue(t.get("annotations"), MAP) : Map.of();
                tools.add(new McpTool(t.path("name").asText(), t.path("title").asText(null),
                        t.path("description").asText(""), schema, ann));
            }
            cursor = r.path("nextCursor").asText(null);
            if (cursor == null || cursor.isBlank()) break;
        }
        return tools;
    }

    public ToolCallResult callTool(String name, Map<String, Object> args, Duration timeout) {
        JsonNode r = request("tools/call", Map.of("name", name, "arguments", args == null ? Map.of() : args), timeout);
        return new ToolCallResult(contentText(r), r.path("isError").asBoolean(false));
    }

    public void ping() {
        request("ping", null, Duration.ofSeconds(10));
    }

    static String contentText(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (!sb.isEmpty()) sb.append('\n');
            switch (c.path("type").asText()) {
                case "text" -> sb.append(c.path("text").asText());
                case "image", "audio" -> sb.append('[').append(c.path("type").asText()).append(' ')
                        .append(c.path("mimeType").asText()).append(']');
                case "resource" -> {
                    JsonNode res = c.path("resource");
                    sb.append(res.hasNonNull("text") ? res.get("text").asText() : "[resource " + res.path("uri").asText() + "]");
                }
                case "resource_link" -> sb.append("[link ").append(c.path("uri").asText()).append(']');
                default -> sb.append(c.toString());
            }
        }
        if (sb.isEmpty() && result.hasNonNull("structuredContent")) sb.append(result.get("structuredContent").toString());
        return sb.toString();
    }
}
