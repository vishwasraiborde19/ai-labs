package com.ailabs.vmadmin;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ailabs.vmadmin.mcp.FleetTools;

/**
 * mcp-vm-admin-agent: control centre for VMs running mcp-vm-worker-agent.
 * UI at http://127.0.0.1:8767/ui, MCP at /mcp (when vmadmin.mcp.token-ref is set).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VmAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(VmAdminApplication.class, args);
    }

    @Bean
    ToolCallbackProvider fleetToolCallbacks(FleetTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Controller
    static class Pages {
        @GetMapping({"/", "/ui", "/ui/"})
        String index() {
            return "forward:/ui/index.html";
        }
    }
}
