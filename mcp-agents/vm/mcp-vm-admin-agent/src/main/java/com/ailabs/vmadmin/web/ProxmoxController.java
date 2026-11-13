package com.ailabs.vmadmin.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.vmadmin.pve.ProxmoxService;

/** The Hypervisor view: the Proxmox cluster, its nodes and every VM and container (read-only). */
@RestController
@RequestMapping("/ui/api/pve")
public class ProxmoxController {

    private final ProxmoxService pve;

    public ProxmoxController(ProxmoxService pve) {
        this.pve = pve;
    }

    /** {@code fresh=true} skips the caches (including guest IPs). */
    @GetMapping
    public Map<String, Object> overview(@RequestParam(defaultValue = "false") boolean fresh) {
        if (fresh) {
            pve.invalidate();
        }
        return pve.overview();
    }
}
