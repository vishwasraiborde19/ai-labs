package com.ailabs.commandcenter.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.commandcenter.Audit;
import com.ailabs.commandcenter.CommandCenterApplication;
import com.ailabs.commandcenter.catalog.CatalogStore;
import com.ailabs.commandcenter.catalog.KindRegistry;
import com.ailabs.commandcenter.catalog.Model;
import com.ailabs.commandcenter.catalog.Model.Flow;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;
import com.ailabs.commandcenter.health.HealthService;
import com.ailabs.commandcenter.integrations.OrchestratorSync;

import jakarta.servlet.http.HttpServletRequest;

/** The UI's API. Reads are open to every allowed client; changes are audited. */
@RestController
@RequestMapping("/ui/api")
public class ApiController {

    private final CatalogStore store;
    private final KindRegistry kinds;
    private final HealthService health;
    private final OrchestratorSync orchestrator;
    private final SecurityFilter security;

    public ApiController(CatalogStore store, KindRegistry kinds, HealthService health, OrchestratorSync orchestrator,
                         SecurityFilter security) {
        this.store = store;
        this.kinds = kinds;
        this.health = health;
        this.orchestrator = orchestrator;
        this.security = security;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", CommandCenterApplication.VERSION);
        m.put("canEdit", security.canEdit(req.getRemoteAddr()));
        m.put("categories", kinds.categories());
        m.put("kinds", kinds.kinds());
        m.put("linkTypes", Model.LINK_TYPES);
        m.put("accessMethods", Model.ACCESS_METHODS);
        m.put("catalogFile", store.file().toString());
        m.put("orchestrator", Map.of("url", orchestrator.url(), "status", orchestrator.status()));
        m.put("health", Map.of("intervalS", health.config().interval().toSeconds(), "certWarnDays", health.config().certWarnDays()));
        return m;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        CatalogStore.Snapshot s = store.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", s.version());
        m.put("systems", s.catalog().systems());
        m.put("flows", s.catalog().flows());
        m.put("problems", store.problems(s.catalog()));
        m.put("health", health.summary());
        return m;
    }

    @GetMapping("/health")
    public Map<String, Object> healthSummary() {
        return health.summary();
    }

    @PostMapping("/health/check")
    public Object check(@RequestParam(required = false) String id) {
        if (id != null && !id.isBlank()) {
            return health.check(id);
        }
        health.checkAll();
        return health.summary();
    }

    // ------------------------------------------------------------------ systems

    public record SystemForm(SystemEntry system, Long version) {}

    @PostMapping("/systems")
    public SystemEntry create(@RequestBody SystemForm f) {
        require(f.system());
        SystemEntry s = store.saveSystem(f.system(), null, f.version());
        Audit.log("SYSTEM_ADD", s.id() + " (" + s.kind() + ")");
        health.check(s);
        return s;
    }

    @PutMapping("/systems/{id}")
    public SystemEntry update(@PathVariable String id, @RequestBody SystemForm f) {
        require(f.system());
        SystemEntry s = store.saveSystem(f.system(), id, f.version());
        Audit.log("SYSTEM_EDIT", id.equals(s.id()) ? id : id + " -> " + s.id());
        health.check(s);
        return s;
    }

    @DeleteMapping("/systems/{id}")
    public Map<String, Object> delete(@PathVariable String id, @RequestParam(required = false) Long version) {
        store.deleteSystem(id, version);
        Audit.log("SYSTEM_DELETE", id);
        return Map.of("deleted", id);
    }

    // ------------------------------------------------------------------ flows

    public record FlowForm(Flow flow, Long version) {}

    @PostMapping("/flows")
    public Flow createFlow(@RequestBody FlowForm f) {
        if (f.flow() == null) {
            throw new IllegalArgumentException("flow is required");
        }
        Flow x = store.saveFlow(f.flow(), null, f.version());
        Audit.log("FLOW_ADD", x.id());
        return x;
    }

    @PutMapping("/flows/{id}")
    public Flow updateFlow(@PathVariable String id, @RequestBody FlowForm f) {
        if (f.flow() == null) {
            throw new IllegalArgumentException("flow is required");
        }
        Flow x = store.saveFlow(f.flow(), id, f.version());
        Audit.log("FLOW_EDIT", id);
        return x;
    }

    @DeleteMapping("/flows/{id}")
    public Map<String, Object> deleteFlow(@PathVariable String id, @RequestParam(required = false) Long version) {
        store.deleteFlow(id, version);
        Audit.log("FLOW_DELETE", id);
        return Map.of("deleted", id);
    }

    // ------------------------------------------------------------------ import / export, integrations, audit

    @GetMapping(value = "/export", produces = "application/yaml")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalog.yml\"")
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(store.exportYaml());
    }

    @PostMapping(value = "/import", consumes = {"application/yaml", "text/plain", "application/octet-stream"})
    public Map<String, Object> importYaml(@RequestBody String yaml, @RequestParam(defaultValue = "merge") String mode) {
        if (!mode.equals("merge") && !mode.equals("replace")) {
            throw new IllegalArgumentException("mode must be merge or replace");
        }
        Map<String, Object> r = store.importYaml(yaml, mode.equals("replace"));
        Audit.log("IMPORT", r.toString());
        health.checkAll();
        return r;
    }

    @PostMapping("/integrations/orchestrator/sync")
    public OrchestratorSync.Status syncOrchestrator() {
        OrchestratorSync.Status s = orchestrator.sync();
        health.checkAll();
        return s;
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam(defaultValue = "300") int lines) {
        return Audit.tail(Math.max(1, Math.min(lines, 5000)));
    }

    private static void require(SystemEntry s) {
        if (s == null) {
            throw new IllegalArgumentException("system is required");
        }
        if (s.name() == null) {
            throw new IllegalArgumentException("name is required");
        }
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(CatalogStore.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(CatalogStore.ConflictException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
