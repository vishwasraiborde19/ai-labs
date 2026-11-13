package com.ailabs.canary;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET  /hello                         one INFO line, returns the pod name
 * POST /log?level=WARN&count=5&msg=x  writes count lines at level (TRACE..ERROR)
 * POST /error                         logs an ERROR with a stack trace
 * POST /burst?lines=100000            writes many lines fast, to watch rotation on the log volume
 */
@RestController
public class LogController {
    private static final Logger log = LoggerFactory.getLogger(LogController.class);
    private static final String POD = System.getenv().getOrDefault("HOSTNAME", "local");

    @GetMapping("/hello")
    public Map<String, String> hello() {
        String id = UUID.randomUUID().toString();
        log.info("hello request id={} pod={}", id, POD);
        return Map.of("pod", POD, "requestId", id);
    }

    @PostMapping("/log")
    public Map<String, Object> write(@RequestParam(defaultValue = "INFO") String level,
                                     @RequestParam(defaultValue = "1") int count,
                                     @RequestParam(defaultValue = "test message") String msg) {
        int n = Math.max(1, Math.min(count, 10_000));
        String lvl = level.toUpperCase();
        for (int i = 1; i <= n; i++) {
            switch (lvl) {
                case "TRACE" -> log.trace("{} [{}/{}]", msg, i, n);
                case "DEBUG" -> log.debug("{} [{}/{}]", msg, i, n);
                case "WARN" -> log.warn("{} [{}/{}]", msg, i, n);
                case "ERROR" -> log.error("{} [{}/{}]", msg, i, n);
                default -> log.info("{} [{}/{}]", msg, i, n);
            }
        }
        return Map.of("pod", POD, "level", lvl, "lines", n);
    }

    @PostMapping("/error")
    public Map<String, String> error() {
        try {
            throw new IllegalStateException("simulated failure for log testing");
        } catch (IllegalStateException e) {
            log.error("simulated error on pod {}", POD, e);
        }
        return Map.of("pod", POD, "logged", "ERROR with stack trace");
    }

    @PostMapping("/burst")
    public Map<String, Object> burst(@RequestParam(defaultValue = "100000") int lines) {
        int n = Math.max(1, Math.min(lines, 2_000_000));
        String pad = "x".repeat(200);
        long t0 = System.currentTimeMillis();
        for (int i = 1; i <= n; i++) {
            log.info("burst line {}/{} {}", i, n, pad);
        }
        return Map.of("pod", POD, "lines", n, "millis", System.currentTimeMillis() - t0);
    }
}
