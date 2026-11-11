package com.ailabs.vmadmin.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.vmadmin.alerts.AlertService;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.metrics.MetricsService;
import com.ailabs.vmadmin.ops.HostAdmin;
import com.ailabs.vmadmin.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;

/** Services, packages, logs, metrics, alerts, schedules, file sync, token rotation and the web terminal. */
@RestController
@RequestMapping("/ui/api")
public class FeaturesController {

    private final HostAdmin admin;
    private final FleetService fleet;
    private final MetricsService metrics;
    private final AlertService alerts;
    private final ScheduleService schedules;

    public FeaturesController(HostAdmin admin, FleetService fleet, MetricsService metrics, AlertService alerts,
                              ScheduleService schedules) {
        this.admin = admin;
        this.fleet = fleet;
        this.metrics = metrics;
        this.alerts = alerts;
        this.schedules = schedules;
    }

    // ------------------------------------------------------------------ overview + metrics

    @GetMapping("/hosts/{h}/sysinfo")
    public Map<String, Object> sysinfo(@PathVariable String h) {
        Map<String, Object> s = fleet.sysInfo(h);
        metrics.record(h, s);
        return s;
    }

    @GetMapping("/hosts/{h}/metrics")
    public List<MetricsService.Sample> metrics(@PathVariable String h, @RequestParam(defaultValue = "24") int hours) {
        fleet.store().require(h);
        return metrics.history(h, Duration.ofHours(Math.max(1, Math.min(hours, 24 * 31))));
    }

    // ------------------------------------------------------------------ services

    @GetMapping("/hosts/{h}/services")
    public List<Map<String, String>> services(@PathVariable String h) {
        return admin.services(h);
    }

    @GetMapping("/hosts/{h}/services/{unit}/status")
    public Map<String, Object> serviceStatus(@PathVariable String h, @PathVariable String unit) {
        return admin.serviceStatus(h, unit);
    }

    public record Confirm(boolean confirm) {}

    @PostMapping("/hosts/{h}/services/{unit}/{action}")
    public Map<String, Object> serviceAction(@PathVariable String h, @PathVariable String unit, @PathVariable String action,
                                             @RequestBody(required = false) Confirm c) {
        return admin.serviceAction(h, unit, action, c != null && c.confirm());
    }

    // ------------------------------------------------------------------ packages

    @GetMapping("/hosts/{h}/packages/upgradable")
    public Map<String, Object> upgradable(@PathVariable String h) {
        return admin.upgradable(h);
    }

    @GetMapping("/hosts/{h}/packages/installed")
    public List<Map<String, String>> installed(@PathVariable String h, @RequestParam String q) {
        return admin.installed(h, q);
    }

    public record PackageForm(String manager, String action, String pkg, boolean confirm) {}

    @PostMapping("/hosts/{h}/packages/action")
    public JsonNode packageAction(@PathVariable String h, @RequestBody PackageForm f) {
        return admin.packageAction(h, f.manager(), f.action(), f.pkg(), f.confirm());
    }

    // ------------------------------------------------------------------ logs

    @GetMapping("/hosts/{h}/logs/journal")
    public Map<String, Object> journal(@PathVariable String h, @RequestParam(required = false) String unit,
                                       @RequestParam(required = false) String priority,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "200") int lines) {
        return admin.journal(h, unit, priority, cursor, lines);
    }

    @GetMapping("/hosts/{h}/logs/file")
    public Map<String, Object> fileTail(@PathVariable String h, @RequestParam String path,
                                        @RequestParam(defaultValue = "-1") long offset) {
        return admin.fileTail(h, path, offset);
    }

    // ------------------------------------------------------------------ token rotation

    @PostMapping("/hosts/{h}/rotate-token")
    public Map<String, Object> rotate(@PathVariable String h) {
        return admin.rotateToken(h);
    }

    // ------------------------------------------------------------------ web terminal

    public record PtyForm(String cwd, Integer cols, Integer rows, String b64) {}

    @PostMapping("/hosts/{h}/pty")
    public JsonNode ptyOpen(@PathVariable String h, @RequestBody PtyForm f) {
        return admin.ptyOpen(h, f.cwd(), f.cols() == null ? 120 : f.cols(), f.rows() == null ? 32 : f.rows());
    }

    @GetMapping("/hosts/{h}/pty/{id}/output")
    public JsonNode ptyOutput(@PathVariable String h, @PathVariable String id, @RequestParam(defaultValue = "0") long offset) {
        return admin.ptyOutput(h, id, offset);
    }

    @PostMapping("/hosts/{h}/pty/{id}/input")
    public JsonNode ptyInput(@PathVariable String h, @PathVariable String id, @RequestBody PtyForm f) {
        if (f.b64() == null) {
            throw new IllegalArgumentException("b64 is required");
        }
        return admin.ptyInput(h, id, f.b64());
    }

    @PostMapping("/hosts/{h}/pty/{id}/resize")
    public JsonNode ptyResize(@PathVariable String h, @PathVariable String id, @RequestBody PtyForm f) {
        return admin.ptyResize(h, id, f.cols() == null ? 120 : f.cols(), f.rows() == null ? 32 : f.rows());
    }

    @DeleteMapping("/hosts/{h}/pty/{id}")
    public JsonNode ptyClose(@PathVariable String h, @PathVariable String id) {
        return admin.ptyClose(h, id);
    }

    // ------------------------------------------------------------------ file sync

    public record SyncForm(String path, String content, List<String> hosts, Boolean backup) {}

    @PostMapping("/sync/preview")
    public Map<String, Object> syncPreview(@RequestBody SyncForm f) {
        return admin.syncPreview(f.path(), f.content(), hosts(f));
    }

    @PostMapping("/sync/apply")
    public Map<String, Object> syncApply(@RequestBody SyncForm f) {
        if (f.path() == null || f.content() == null) {
            throw new IllegalArgumentException("path and content are required");
        }
        return admin.syncApply(f.path(), f.content(), hosts(f), f.backup() == null || f.backup());
    }

    private List<String> hosts(SyncForm f) {
        List<String> h = fleet.select(f.hosts(), null, null);
        if (h.isEmpty()) {
            throw new IllegalArgumentException("pick at least one host");
        }
        return h;
    }

    // ------------------------------------------------------------------ alerts

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", alerts.active());
        m.put("history", alerts.history());
        m.put("config", alerts.config());
        m.put("webhook", alerts.webhookConfigured());
        return m;
    }

    @PostMapping("/alerts/test")
    public Map<String, Object> alertTest() {
        return alerts.test();
    }

    // ------------------------------------------------------------------ schedules

    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules() {
        return schedules.list().stream().map(schedules::view).toList();
    }

    public record ScheduleForm(ScheduleService.Schedule schedule, boolean confirm) {}

    @PostMapping("/schedules")
    public Map<String, Object> saveSchedule(@RequestBody ScheduleForm f) {
        if (f.schedule() == null) {
            throw new IllegalArgumentException("schedule is required");
        }
        return schedules.view(schedules.save(f.schedule(), f.confirm()));
    }

    @DeleteMapping("/schedules/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable String id) {
        schedules.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping("/schedules/{id}/run")
    public ScheduleService.Run runSchedule(@PathVariable String id) {
        return schedules.runNow(id, com.ailabs.vmadmin.Audit.actor());
    }

    @GetMapping("/schedules/{id}/runs")
    public List<ScheduleService.Run> scheduleRuns(@PathVariable String id) {
        schedules.get(id);
        return schedules.runs(id);
    }
}
