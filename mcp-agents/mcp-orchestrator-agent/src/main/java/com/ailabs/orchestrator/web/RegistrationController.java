package com.ailabs.orchestrator.web;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.orchestrator.registry.AgentRegistry;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Agents announce themselves here (bearer token checked by {@link SecurityFilter}). New agents wait in the inbox
 * until approved in the UI; an already approved agent at the same URL is just refreshed (heartbeat).
 *
 * <pre>
 * POST /registry/register
 * Authorization: Bearer &lt;registration token&gt;
 * {"id":"kube","name":"Kira","role":"Kubernetes engineer","url":"http://10.0.0.5:8767/mcp","token":"...","tags":["k8s"]}
 * </pre>
 */
@RestController
public class RegistrationController {

    private final AgentRegistry registry;

    public RegistrationController(AgentRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/registry/register")
    public Map<String, Object> register(@RequestBody AgentRegistry.Registration body, HttpServletRequest req) {
        return registry.register(body, req.getRemoteAddr());
    }
}
