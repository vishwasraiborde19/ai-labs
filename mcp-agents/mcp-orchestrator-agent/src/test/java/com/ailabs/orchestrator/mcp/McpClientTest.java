package com.ailabs.orchestrator.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class McpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsResponseFromEventStream() throws IOException {
        String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\n"
                + "data: \"result\":{\"ok\":true}}\n\n";
        JsonNode n = HttpMcpClient.readSse(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), 7);
        assertTrue(n.path("result").path("ok").asBoolean());
    }

    @Test
    void joinsContentBlocks() throws IOException {
        JsonNode r = JSON.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"image\",\"mimeType\":\"image/png\"}]}");
        assertEquals("a\n[image image/png]", McpClient.contentText(r));
    }

    /** A tiny streamable-HTTP MCP server: session header, JSON replies for most calls, SSE for tools/call. */
    @Test
    void talksToHttpServer() throws Exception {
        AtomicInteger inits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            JsonNode req = JSON.readTree(ex.getRequestBody());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer t0ken".equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            String method = req.path("method").asText();
            if (!req.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            Object result = switch (method) {
                case "initialize" -> {
                    inits.incrementAndGet();
                    ex.getResponseHeaders().add("Mcp-Session-Id", "s1");
                    yield Map.of("protocolVersion", "2025-06-18", "serverInfo", Map.of("name", "fake", "version", "1"),
                            "instructions", "use tools");
                }
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "Echo",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("msg", Map.of("type", "string"))))));
                case "tools/call" -> Map.of("content", List.of(Map.of("type", "text",
                        "text", "echo: " + req.path("params").path("arguments").path("msg").asText())));
                default -> Map.of();
            };
            byte[] body;
            String json = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", req.get("id").asLong(), "result", result));
            if ("tools/call".equals(method)) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                body = json.getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            HttpMcpClient c = new HttpMcpClient(uri, () -> "t0ken");
            McpServerInfo info = c.initialize();
            assertEquals("fake", info.name());
            assertEquals("use tools", info.instructions());
            List<McpTool> tools = c.listTools();
            assertEquals("echo", tools.get(0).name());
            ToolCallResult r = c.callTool("echo", Map.of("msg", "hi"), Duration.ofSeconds(5));
            assertEquals("echo: hi", r.text());
            assertFalse(r.error());
            assertEquals(1, inits.get());

            HttpMcpClient bad = new HttpMcpClient(uri, () -> "wrong");
            org.junit.jupiter.api.Assertions.assertThrows(McpException.class, bad::initialize);
        } finally {
            server.stop(0);
        }
    }
}
