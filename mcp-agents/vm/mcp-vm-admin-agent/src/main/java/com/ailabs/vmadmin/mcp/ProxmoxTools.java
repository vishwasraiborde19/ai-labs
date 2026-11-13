package com.ailabs.vmadmin.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.pve.ProxmoxService;

/** The Proxmox hypervisor over MCP (read-only for now). */
@Component
public class ProxmoxTools {

    private final ProxmoxService pve;

    public ProxmoxTools(ProxmoxService pve) {
        this.pve = pve;
    }

    @Tool(name = "pve_overview", description = "Proxmox cluster: name, quorum, and each hypervisor node with status, CPU, memory, disk and uptime, plus guest counts.")
    public Map<String, Object> overview() {
        Audit.actor("mcp");
        try {
            Map<String, Object> o = pve.overview();
            if (!Boolean.TRUE.equals(o.get("configured"))) {
                return Map.of("configured", false, "hint", "Set vmadmin.proxmox.endpoints and token-ref in the control centre's config.yml");
            }
            Map<String, Object> m = new LinkedHashMap<>(o);
            m.remove("guests");
            return m;
        } finally {
            Audit.clear();
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "pve_vms", description = "Every VM (qemu) and container (lxc) on the Proxmox cluster: vmid, name, node, status, CPU (fraction of maxcpu), memory and disk in bytes, uptime, tags, IP addresses, and the fleet host name when a worker agent runs on it.")
    public List<Map<String, Object>> vms(@ToolParam(description = "only guests with this status: running, stopped, paused", required = false) String status,
                                         @ToolParam(description = "only guests on this node", required = false) String node,
                                         @ToolParam(description = "true to skip the 5 s cache and re-read guest IPs", required = false) Boolean fresh) {
        Audit.actor("mcp");
        try {
            if (Boolean.TRUE.equals(fresh)) {
                pve.invalidate();
            }
            Map<String, Object> o = pve.overview();
            if (!Boolean.TRUE.equals(o.get("configured"))) {
                throw new IllegalStateException("Proxmox is not configured: set vmadmin.proxmox.endpoints and token-ref in config.yml");
            }
            return ((List<Map<String, Object>>) o.get("guests")).stream()
                    .filter(g -> status == null || status.isBlank() || status.equalsIgnoreCase(String.valueOf(g.get("status"))))
                    .filter(g -> node == null || node.isBlank() || node.equalsIgnoreCase(String.valueOf(g.get("node"))))
                    .toList();
        } finally {
            Audit.clear();
        }
    }
}
