package com.ailabs.vmadmin.ops;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.agent.AgentClient;
import com.ailabs.vmadmin.agent.AgentException;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.guard.CommandGuard;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything the control centre can do to a VM, used by both the UI and the MCP tools so the same
 * rules apply: read-only hosts refuse changes, risky commands need {@code confirm}, and every action
 * lands in the audit log.
 */
@Service
public class Operations {

    /** A risky command was sent without {@code confirm=true}. */
    public static class NeedsConfirmation extends RuntimeException {
        public NeedsConfirmation(String reason) {
            super(reason);
        }
    }

    public static final int MAX_TIMEOUT_S = 3600;

    private final FleetService fleet;
    private final CommandGuard guard;

    public Operations(FleetService fleet, CommandGuard guard) {
        this.fleet = fleet;
        this.guard = guard;
    }

    public FleetService fleet() {
        return fleet;
    }

    private void confirmIfRisky(String command, boolean confirm) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        if (!confirm) {
            guard.check(command).ifPresent(reason -> {
                throw new NeedsConfirmation(reason);
            });
        }
    }

    // ------------------------------------------------------------------ commands

    public JsonNode run(String host, String command, String cwd, Integer timeoutSeconds, boolean confirm) {
        confirmIfRisky(command, confirm);
        return doRun(host, command, cwd, timeoutSeconds, confirm && guard.check(command).isPresent());
    }

    private JsonNode doRun(String host, String command, String cwd, Integer timeoutSeconds, boolean confirmedRisky) {
        AgentClient c = fleet.writable(host);
        int t = timeoutSeconds == null ? 120 : Math.max(1, Math.min(timeoutSeconds, MAX_TIMEOUT_S));
        Audit.log(host, "RUN", (confirmedRisky ? "(risky, confirmed) " : "") + "cwd=" + (cwd == null || cwd.isBlank() ? "-" : cwd) + " cmd=" + command);
        JsonNode r = c.run(command, cwd, t);
        Audit.log(host, "RUN", "exit=" + r.path("exit_code").asText("null") + " duration=" + r.path("duration_s").asText("-"));
        return r;
    }

    public Map<String, Object> broadcast(List<String> hosts, String command, String cwd, Integer timeoutSeconds, boolean confirm) {
        confirmIfRisky(command, confirm);
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("no hosts selected");
        }
        for (String h : hosts) {
            fleet.writable(h);  // fail fast on read-only hosts before running anywhere
        }
        boolean risky = confirm && guard.check(command).isPresent();
        Audit.log(String.join(",", hosts), "BROADCAST", (risky ? "(risky, confirmed) " : "") + "cmd=" + command);
        return fleet.fanOut(hosts, h -> doRun(h, command, cwd, timeoutSeconds, risky));
    }

    public JsonNode startJob(String host, String command, String cwd, boolean confirm) {
        confirmIfRisky(command, confirm);
        AgentClient c = fleet.writable(host);
        Audit.log(host, "JOB_START", "cmd=" + command);
        return c.startJob(command, cwd);
    }

    public JsonNode jobs(String host) {
        return fleet.client(host).jobs();
    }

    public JsonNode job(String host, String id, int tail) {
        return fleet.client(host).job(id, Math.max(0, Math.min(tail, 5000)));
    }

    public JsonNode killJob(String host, String id) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "JOB_KILL", id);
        return c.killJob(id);
    }

    // ------------------------------------------------------------------ files

    public JsonNode list(String host, String path, boolean hidden) {
        return fleet.client(host).list(path, hidden);
    }

    public JsonNode read(String host, String path, long maxBytes) {
        Audit.log(host, "READ", path);
        return fleet.client(host).read(path, maxBytes);
    }

    public HttpResponse<InputStream> download(String host, String path) {
        Audit.log(host, "DOWNLOAD", path);
        return fleet.client(host).download(path);
    }

    public JsonNode saveText(String host, String path, String content, boolean backup) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "WRITE", path + " (" + content.length() + " chars, backup=" + backup + ")");
        return c.writeText(path, content, backup);
    }

    public JsonNode upload(String host, String path, Supplier<InputStream> body, long length, boolean overwrite) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "UPLOAD", path + " (" + length + " bytes, overwrite=" + overwrite + ")");
        return c.write(path, body, length, true, overwrite, false);
    }

    public JsonNode mkdir(String host, String path) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "MKDIR", path);
        return c.mkdir(path);
    }

    public JsonNode rename(String host, String from, String to) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "RENAME", from + " -> " + to);
        return c.rename(from, to, false);
    }

    public JsonNode delete(String host, String path, boolean recursive) {
        AgentClient c = fleet.writable(host);
        Audit.log(host, "DELETE", path + (recursive ? " (recursive)" : ""));
        return c.delete(path, recursive);
    }

    /** The VM agent's own audit log (what every token did there). */
    public Map<String, Object> agentAudit(String host, int lines) {
        FleetService.Status s = fleet.refresh(host);
        if (s.base() == null) {
            throw new AgentException(502, host + " is not reachable: " + s.message());
        }
        String file = s.base() + "/audit.log";
        int n = Math.max(10, Math.min(lines, 2000));
        JsonNode r = fleet.client(host).run("tail -n " + n + " '" + file.replace("'", "'\\''") + "'", "/", 30);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file);
        out.put("lines", r.path("output").asText("").lines().toList());
        return out;
    }
}
