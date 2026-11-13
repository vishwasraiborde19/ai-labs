package com.ailabs.orchestrator.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP over stdio: starts the agent as a child process (e.g. {@code java -jar mcp-kube-agent.jar}) and exchanges
 * newline-delimited JSON-RPC on its stdin/stdout. Lines that are not JSON-RPC are ignored; stderr is kept for errors.
 */
public final class StdioMcpClient extends McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    private final List<String> command;
    private final Map<String, String> env;
    private final Path workdir;
    private final String label;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private volatile Process process;
    private BufferedWriter stdin;

    public StdioMcpClient(String label, List<String> command, Map<String, String> env, Path workdir) {
        this.label = label;
        this.command = List.copyOf(command);
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.workdir = workdir;
    }

    private synchronized void start() {
        if (process != null && process.isAlive()) return;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        if (workdir != null) pb.directory(workdir.toFile());
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new McpException("cannot start " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Process p = process;
        Thread.ofVirtual().name("mcp-stdout-" + label).start(() -> readStdout(p));
        Thread.ofVirtual().name("mcp-stderr-" + label).start(() -> readStderr(p));
        log.info("started stdio agent {} (pid {})", label, p.pid());
    }

    private void readStdout(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("{")) continue;
                JsonNode n;
                try {
                    n = JSON.readTree(line);
                } catch (IOException e) {
                    continue;
                }
                if (n.has("id") && (n.has("result") || n.has("error"))) {
                    CompletableFuture<JsonNode> f = pending.remove(n.get("id").asLong());
                    if (f != null) f.complete(n);
                } else if (n.has("id") && n.has("method")) {
                    answerServerRequest(n);
                }
            }
        } catch (IOException ignored) {
            // process went away
        }
        McpException gone = new McpException("agent process exited" + exitInfo(p));
        pending.values().forEach(f -> f.completeExceptionally(gone));
        pending.clear();
    }

    /** The server may ping us; anything else (sampling, roots) is not supported by the orchestrator. */
    private void answerServerRequest(JsonNode req) {
        ObjectNode reply = JSON.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", req.get("id"));
        if ("ping".equals(req.path("method").asText())) {
            reply.set("result", JSON.createObjectNode());
        } else {
            ObjectNode err = reply.putObject("error");
            err.put("code", -32601);
            err.put("message", "not supported by the orchestrator");
        }
        try {
            write(reply);
        } catch (McpException ignored) {
            // stdin closed
        }
    }

    private void readStderr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > 30) stderrTail.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // process went away
        }
    }

    private String exitInfo(Process p) {
        StringBuilder sb = new StringBuilder();
        try {
            if (p.waitFor(2, TimeUnit.SECONDS)) sb.append(" with code ").append(p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (stderrTail) {
            if (!stderrTail.isEmpty()) sb.append(": ").append(String.join(" | ", stderrTail));
        }
        return sb.toString();
    }

    private void write(ObjectNode msg) {
        synchronized (writeLock) {
            try {
                stdin.write(JSON.writeValueAsString(msg));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new McpException("cannot write to the agent process: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected JsonNode send(ObjectNode request, Duration timeout) {
        start();
        long id = request.path("id").asLong();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        write(request);
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException(request.path("method").asText() + " timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof McpException m ? m : new McpException(String.valueOf(c), c);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        }
    }

    @Override
    protected void notify(ObjectNode notification) {
        start();
        write(notification);
    }

    @Override
    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    @Override
    public synchronized void close() {
        Process p = process;
        if (p == null) return;
        try {
            p.getOutputStream().close();          // MCP stdio shutdown: close stdin, then terminate
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            p.destroyForcibly();
        }
        process = null;
    }
}
