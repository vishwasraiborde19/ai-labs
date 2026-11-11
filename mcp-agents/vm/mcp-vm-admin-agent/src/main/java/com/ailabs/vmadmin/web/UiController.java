package com.ailabs.vmadmin.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.agent.AgentClient;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.FleetStore;
import com.ailabs.vmadmin.fleet.Host;
import com.ailabs.vmadmin.ops.Operations;
import com.ailabs.vmadmin.secrets.SecretResolver;
import com.ailabs.vmadmin.secrets.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** JSON API behind the control-centre UI ({@code /ui/api}). */
@RestController
@RequestMapping("/ui/api")
public class UiController {

    private final Operations ops;
    private final FleetService fleet;
    private final FleetStore store;
    private final TokenStore tokens;
    private final SecretResolver secrets;
    private final AdminProperties props;

    public UiController(Operations ops, TokenStore tokens, SecretResolver secrets, AdminProperties props) {
        this.ops = ops;
        this.fleet = ops.fleet();
        this.store = fleet.store();
        this.tokens = tokens;
        this.secrets = secrets;
        this.props = props;
    }

    // ------------------------------------------------------------------ session and fleet

    @GetMapping("/session")
    public Map<String, Object> session() {
        FleetService.Bundle b = FleetService.bundle();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.ui().auth());
        m.put("bundledAgentVersion", b == null ? null : b.version());
        m.put("snippets", props.snippets());
        m.put("tokenSchemes", SecretResolver.SCHEMES);
        m.put("mcpEnabled", props.mcp().tokenRef() != null);
        m.put("alertsWebhook", props.alerts().webhookRef() != null);
        return m;
    }

    @GetMapping("/fleet")
    public Map<String, Object> fleetView() {
        FleetStore.Snapshot s = store.snapshot();
        Map<String, FleetService.Status> st = new LinkedHashMap<>();
        fleet.statuses().forEach(x -> st.put(x.host(), x));
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (Host h : s.fleet().hosts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", h);
            m.put("status", st.get(h.name()));
            hosts.add(m);
        }
        return Map.of("version", s.version(), "file", store.file().toString(), "hosts", hosts);
    }

    public record HostForm(Host host, String token, String originalName, String version) {}

    @PostMapping("/fleet/hosts")
    public Map<String, Object> saveHost(@RequestBody HostForm f) {
        if (f.host() == null) {
            throw new IllegalArgumentException("host is required");
        }
        Host h = f.host();
        String oldName = f.originalName() == null || f.originalName().isBlank() ? null : f.originalName();
        boolean pasted = f.token() != null && !f.token().isBlank();
        if (pasted) {
            h = h.withTokenRef("store:" + h.name());
        } else if (oldName != null && !oldName.equals(h.name()) && ("store:" + oldName).equals(h.tokenRef())) {
            h = h.withTokenRef("store:" + h.name());  // renamed: carry the stored token over
        }
        String ref = h.tokenRef();
        if (ref != null && SecretResolver.SCHEMES.stream().noneMatch(sc -> ref.startsWith(sc + ":"))) {
            throw new IllegalArgumentException("token reference must start with one of " + SecretResolver.SCHEMES.stream().map(sc -> sc + ":").toList());
        }
        if (!h.problems().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", h.problems()));
        }
        String carried = null;
        if (!pasted && h.tokenRef().startsWith("store:") && oldName != null && !oldName.equals(h.name())) {
            carried = tokens.get(oldName).orElse(null);
        }
        if (pasted) {
            tokens.put(h.name(), f.token().trim());
        } else if (carried != null) {
            tokens.put(h.name(), carried);
        }
        FleetStore.Snapshot s = store.upsert(h, oldName, f.version());
        if (oldName != null && !oldName.equals(h.name())) {
            fleet.forget(oldName);
            tokens.remove(oldName);
        }
        fleet.forget(h.name());
        Audit.log(h.name(), oldName == null ? "HOST_ADD" : "HOST_EDIT", h.url() + (pasted ? " (new token)" : ""));
        FleetService.Status st = fleet.refresh(h.name());
        return Map.of("version", s.version(), "status", st);
    }

    @DeleteMapping("/fleet/hosts/{name}")
    public Map<String, Object> removeHost(@PathVariable String name, @RequestParam(required = false) String version) {
        Host h = store.require(name);
        FleetStore.Snapshot s = store.remove(name, version);
        if (("store:" + name).equals(h.tokenRef())) {
            tokens.remove(name);
        }
        fleet.forget(name);
        Audit.log(name, "HOST_REMOVE", h.url());
        return Map.of("version", s.version());
    }

    /** Try a host's settings (and pasted token) without saving them. */
    @PostMapping("/fleet/test")
    public Map<String, Object> test(@RequestBody HostForm f) {
        Host h = f.host();
        if (h == null || h.url() == null) {
            throw new IllegalArgumentException("url is required");
        }
        String token;
        if (f.token() != null && !f.token().isBlank()) {
            token = f.token().trim();
        } else if (h.tokenRef() != null) {
            token = secrets.resolve(h.tokenRef(), "test " + h.name());
        } else {
            throw new IllegalArgumentException("paste a token or give a token reference");
        }
        AgentClient c = new AgentClient(h, () -> token);
        Map<String, Object> out = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        JsonNode health = c.health(Duration.ofSeconds(5));
        out.put("reachable", true);
        out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        out.put("version", health.path("version").asText(null));
        JsonNode info = c.info(Duration.ofSeconds(5));
        out.put("tokenAccepted", true);
        out.put("agentName", info.path("name").asText(null));
        out.put("hostname", info.path("host").asText(null));
        out.put("files", health.path("features").toString().contains("fs"));
        return out;
    }

    @PostMapping("/fleet/refresh")
    public List<FleetService.Status> refreshAll() {
        fleet.refreshAll();
        return fleet.statuses();
    }

    // ------------------------------------------------------------------ one host

    @GetMapping("/hosts/{h}/status")
    public FleetService.Status status(@PathVariable String h) {
        return fleet.refresh(h);
    }

    public record RunForm(String command, String cwd, Integer timeoutSeconds, boolean confirm) {}

    @PostMapping("/hosts/{h}/run")
    public JsonNode run(@PathVariable String h, @RequestBody RunForm f) {
        return ops.run(h, f.command(), f.cwd(), f.timeoutSeconds(), f.confirm());
    }

    @GetMapping("/hosts/{h}/jobs")
    public JsonNode jobs(@PathVariable String h) {
        return ops.jobs(h);
    }

    @PostMapping("/hosts/{h}/jobs")
    public JsonNode startJob(@PathVariable String h, @RequestBody RunForm f) {
        return ops.startJob(h, f.command(), f.cwd(), f.confirm());
    }

    @GetMapping("/hosts/{h}/jobs/{id}")
    public JsonNode job(@PathVariable String h, @PathVariable String id, @RequestParam(defaultValue = "300") int tail) {
        return ops.job(h, id, tail);
    }

    @DeleteMapping("/hosts/{h}/jobs/{id}")
    public JsonNode killJob(@PathVariable String h, @PathVariable String id) {
        return ops.killJob(h, id);
    }

    @GetMapping("/hosts/{h}/agent-audit")
    public Map<String, Object> agentAudit(@PathVariable String h, @RequestParam(defaultValue = "200") int lines) {
        return ops.agentAudit(h, lines);
    }

    @PostMapping("/hosts/{h}/upgrade")
    public Map<String, Object> upgrade(@PathVariable String h) {
        return fleet.upgrade(h);
    }

    // ------------------------------------------------------------------ files

    @GetMapping("/hosts/{h}/fs/list")
    public JsonNode list(@PathVariable String h, @RequestParam(required = false) String path,
                         @RequestParam(defaultValue = "true") boolean hidden) {
        return ops.list(h, path, hidden);
    }

    @GetMapping("/hosts/{h}/fs/read")
    public JsonNode read(@PathVariable String h, @RequestParam String path,
                         @RequestParam(defaultValue = "1000000") long maxBytes) {
        return ops.read(h, path, maxBytes);
    }

    @GetMapping("/hosts/{h}/fs/download")
    public void download(@PathVariable String h, @RequestParam String path, HttpServletResponse res) throws IOException {
        HttpResponse<InputStream> r = ops.download(h, path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        res.setContentType("application/octet-stream");
        res.setHeader("Content-Disposition", ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        r.headers().firstValueAsLong("content-length").ifPresent(res::setContentLengthLong);
        try (InputStream in = r.body(); OutputStream out = res.getOutputStream()) {
            in.transferTo(out);
        }
    }

    public record SaveForm(String path, String content, Boolean backup) {}

    @PostMapping("/hosts/{h}/fs/save")
    public JsonNode save(@PathVariable String h, @RequestBody SaveForm f) {
        if (f.path() == null || f.content() == null) {
            throw new IllegalArgumentException("path and content are required");
        }
        return ops.saveText(h, f.path(), f.content(), f.backup() == null || f.backup());
    }

    @PutMapping("/hosts/{h}/fs/file")
    public JsonNode upload(@PathVariable String h, @RequestParam String path,
                           @RequestParam(defaultValue = "false") boolean overwrite, HttpServletRequest req) {
        return ops.upload(h, path, () -> {
            try {
                return req.getInputStream();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, req.getContentLengthLong(), overwrite);
    }

    public record PathForm(String path, String to, Boolean recursive) {}

    @PostMapping("/hosts/{h}/fs/mkdir")
    public JsonNode mkdir(@PathVariable String h, @RequestBody PathForm f) {
        return ops.mkdir(h, f.path());
    }

    @PostMapping("/hosts/{h}/fs/rename")
    public JsonNode rename(@PathVariable String h, @RequestBody PathForm f) {
        return ops.rename(h, f.path(), f.to());
    }

    @PostMapping("/hosts/{h}/fs/delete")
    public JsonNode delete(@PathVariable String h, @RequestBody PathForm f) {
        return ops.delete(h, f.path(), Boolean.TRUE.equals(f.recursive()));
    }

    // ------------------------------------------------------------------ fleet-wide

    public record BroadcastForm(List<String> hosts, String group, String tag, String command, String cwd,
                                Integer timeoutSeconds, boolean confirm) {}

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@RequestBody BroadcastForm f) {
        List<String> targets = fleet.select(f.hosts(), f.group(), f.tag());
        return ops.broadcast(targets, f.command(), f.cwd(), f.timeoutSeconds(), f.confirm());
    }

    @GetMapping("/audit")
    public List<String> audit(@RequestParam(defaultValue = "300") int lines) {
        return Audit.tail(Math.max(10, Math.min(lines, 5000)));
    }
}
