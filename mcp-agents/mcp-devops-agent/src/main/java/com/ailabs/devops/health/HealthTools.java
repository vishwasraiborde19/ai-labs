package com.ailabs.devops.health;

import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Skill: health checks. */
@Component
public class HealthTools {

    private final HealthChecker checker;

    public HealthTools(HealthChecker checker) {
        this.checker = checker;
    }

    @Tool(name = "health_check_http", description = "Probe an HTTP endpoint with retries until it returns the expected status "
            + "(and optionally contains some text). Returns every attempt with status and latency.")
    public Map<String, Object> check(@ToolParam(description = "URL, e.g. http://my-app.dev.example.com/actuator/health") String url,
                                     @ToolParam(required = false, description = "Expected status, default 200") Integer expectedStatus,
                                     @ToolParam(required = false, description = "Text the body must contain, e.g. \"UP\"") String bodyContains,
                                     @ToolParam(required = false, description = "Attempts, default 1, max 60") Integer attempts,
                                     @ToolParam(required = false, description = "Seconds between attempts, default 5") Integer intervalSeconds,
                                     @ToolParam(required = false, description = "Per-request timeout seconds, default 5") Integer timeoutSeconds,
                                     @ToolParam(required = false, description = "Accept self-signed certificates") Boolean insecureTls) {
        return checker.check(url, expectedStatus, bodyContains, attempts, intervalSeconds, timeoutSeconds, insecureTls);
    }
}
