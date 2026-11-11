package com.ailabs.vmadmin.mcp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.Host;
import com.ailabs.vmadmin.alerts.AlertService;
import com.ailabs.vmadmin.ops.HostAdmin;
import com.ailabs.vmadmin.ops.Operations;

/** The fleet over MCP, for Claude Code and other agents. Same rules as the UI. */
@Component
public class FleetTools {

    private final Operations ops;
    private final FleetService fleet;
    private final HostAdmin admin;
    private final AlertService alerts;

    public FleetTools(Operations ops, HostAdmin admin, AlertService alerts) {
        this.ops = ops;
        this.fleet = ops.fleet();
        this.admin = admin;
        this.alerts = alerts;
    }

    /** Tool calls may run off the request thread, so tag the audit actor here. */
    private static <T> T asMcp(Supplier<T> s) {
        Audit.actor("mcp");
        try {
            return s.get();
        } catch (Operations.NeedsConfirmation e) {
            throw new IllegalStateException("This command " + e.getMessage()
                    + ". Ask the user, then call again with confirm=true if they agree.");
        } finally {
            Audit.clear();
        }
    }

    @Tool(name = "fleet_hosts", description = "List the VMs in the fleet with group, tags, read-only flag and last known status (state, agent version, uptime).")
    public List<Map<String, Object>> hosts() {
        return asMcp(() -> {
            Map<String, FleetService.Status> st = new LinkedHashMap<>();
            fleet.statuses().forEach(s -> st.put(s.host(), s));
            return fleet.store().snapshot().fleet().hosts().stream().map(h -> describe(h, st.get(h.name()))).toList();
        });
    }

    @Tool(name = "fleet_refresh", description = "Re-check every VM now and return their status.")
    public List<FleetService.Status> refresh() {
        return asMcp(() -> {
            fleet.refreshAll();
            return fleet.statuses();
        });
    }

    @Tool(name = "host_sysinfo", description = "OS, kernel, uptime, load, CPU count, memory, root disk, IPs, failed systemd units and whether a reboot is pending, for one VM.")
    public Map<String, Object> sysinfo(@ToolParam(description = "host name from fleet_hosts") String host) {
        return asMcp(() -> fleet.sysInfo(host));
    }

    @Tool(name = "host_run", description = "Run a bash command on one VM and return exit code and output. Risky commands (reboot, rm -rf /, stopping services, ...) are refused unless confirm=true; only set confirm after the user agreed.")
    public Object run(@ToolParam(description = "host name") String host,
                      @ToolParam(description = "bash command") String command,
                      @ToolParam(description = "working directory", required = false) String cwd,
                      @ToolParam(description = "timeout in seconds, default 120, max 3600", required = false) Integer timeoutSeconds,
                      @ToolParam(description = "true only after the user confirmed a risky command", required = false) Boolean confirm) {
        return asMcp(() -> ops.run(host, command, cwd, timeoutSeconds, Boolean.TRUE.equals(confirm)));
    }

    @Tool(name = "fleet_run", description = "Run the same bash command on several VMs in parallel: give host names (comma separated), a group, or a tag. Returns each host's result.")
    public Map<String, Object> fleetRun(@ToolParam(description = "comma-separated host names", required = false) String hosts,
                                        @ToolParam(description = "every host in this group", required = false) String group,
                                        @ToolParam(description = "every host with this tag", required = false) String tag,
                                        @ToolParam(description = "bash command") String command,
                                        @ToolParam(description = "timeout in seconds per host, default 120", required = false) Integer timeoutSeconds,
                                        @ToolParam(description = "true only after the user confirmed a risky command", required = false) Boolean confirm) {
        return asMcp(() -> {
            List<String> names = hosts == null || hosts.isBlank() ? List.of()
                    : Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return ops.broadcast(fleet.select(names, group, tag), command, null, timeoutSeconds, Boolean.TRUE.equals(confirm));
        });
    }

    @Tool(name = "host_list_dir", description = "List a directory on a VM (name, type, size, mode, owner, mtime). Needs agent 2.1.0+.")
    public Object listDir(@ToolParam(description = "host name") String host,
                          @ToolParam(description = "absolute path; empty for the agent's default directory", required = false) String path) {
        return asMcp(() -> ops.list(host, path, true));
    }

    @Tool(name = "host_read_file", description = "Read a text file on a VM (up to 1 MB). Needs agent 2.1.0+.")
    public Object readFile(@ToolParam(description = "host name") String host,
                           @ToolParam(description = "absolute path") String path) {
        return asMcp(() -> ops.read(host, path, 1_000_000));
    }

    @Tool(name = "host_write_file", description = "Write a text file on a VM, keeping a .bak-<timestamp> copy of the old one. Refused on read-only hosts. Needs agent 2.1.0+.")
    public Object writeFile(@ToolParam(description = "host name") String host,
                            @ToolParam(description = "absolute path") String path,
                            @ToolParam(description = "full new content") String content) {
        return asMcp(() -> ops.saveText(host, path, content, true));
    }

    @Tool(name = "host_start_job", description = "Start a long-running command in the background on a VM; returns a job_id for host_job_status.")
    public Object startJob(@ToolParam(description = "host name") String host,
                           @ToolParam(description = "bash command") String command,
                           @ToolParam(description = "true only after the user confirmed a risky command", required = false) Boolean confirm) {
        return asMcp(() -> ops.startJob(host, command, null, Boolean.TRUE.equals(confirm)));
    }

    @Tool(name = "host_job_status", description = "Running state, exit code and last output lines of a background job on a VM. Without job_id, lists recent jobs.")
    public Object jobStatus(@ToolParam(description = "host name") String host,
                            @ToolParam(description = "job id", required = false) String jobId,
                            @ToolParam(description = "output lines to return, default 100", required = false) Integer tail) {
        return asMcp(() -> jobId == null || jobId.isBlank() ? ops.jobs(host) : ops.job(host, jobId, tail == null ? 100 : tail));
    }

    @Tool(name = "host_kill_job", description = "Stop a background job on a VM (SIGTERM to its process group).")
    public Object killJob(@ToolParam(description = "host name") String host, @ToolParam(description = "job id") String jobId) {
        return asMcp(() -> ops.killJob(host, jobId));
    }

    @Tool(name = "host_services", description = "List systemd services on a VM: unit, active state, sub state, enabled at boot, description.")
    public Object services(@ToolParam(description = "host name") String host) {
        return asMcp(() -> admin.services(host));
    }

    @Tool(name = "host_service_action", description = "start, stop, restart, reload, enable or disable a systemd service on a VM (uses sudo -n). stop and disable need confirm=true after the user agreed.")
    public Object serviceAction(@ToolParam(description = "host name") String host,
                                @ToolParam(description = "unit, e.g. nginx.service") String unit,
                                @ToolParam(description = "start | stop | restart | reload | enable | disable") String action,
                                @ToolParam(description = "true only after the user confirmed", required = false) Boolean confirm) {
        return asMcp(() -> admin.serviceAction(host, unit, action, Boolean.TRUE.equals(confirm)));
    }

    @Tool(name = "host_journal", description = "Recent systemd journal lines on a VM, optionally for one unit and minimum priority (err, warning, notice, info).")
    public Object journal(@ToolParam(description = "host name") String host,
                          @ToolParam(description = "unit, e.g. nginx", required = false) String unit,
                          @ToolParam(description = "minimum priority", required = false) String priority,
                          @ToolParam(description = "number of lines, default 200", required = false) Integer lines) {
        return asMcp(() -> admin.journal(host, unit, priority, null, lines == null ? 200 : lines));
    }

    @Tool(name = "host_updates", description = "Package manager (apt/dnf/yum) and the packages with updates available on a VM.")
    public Object updates(@ToolParam(description = "host name") String host) {
        return asMcp(() -> admin.upgradable(host));
    }

    @Tool(name = "fleet_alerts", description = "Active alerts across the fleet (host offline, disk, memory, load, failed units, reboot required) and recently resolved ones.")
    public Map<String, Object> alerts() {
        return asMcp(() -> Map.of("active", alerts.active(), "recent", alerts.history().stream().limit(30).toList()));
    }

    private static Map<String, Object> describe(Host h, FleetService.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", h.name());
        m.put("url", h.url());
        m.put("group", h.group());
        m.put("tags", h.tags());
        m.put("readOnly", h.readOnly());
        if (s != null) {
            m.put("state", s.state());
            m.put("version", s.version());
            m.put("uptimeS", s.uptimeS());
            m.put("message", s.message());
        }
        return m;
    }
}
