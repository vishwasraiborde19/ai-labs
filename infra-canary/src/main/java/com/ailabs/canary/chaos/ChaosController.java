package com.ailabs.canary.chaos;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chaos API (the console at / uses it). Read: GET /chaos/state. Changes are POST/DELETE, refused with 403
 * unless canary.chaos.enabled=true, and need header X-Chaos-Token when canary.chaos.token is set.
 * {@code seconds} = automatic revert after that many seconds, 0 = until cleared. Every change returns the new state.
 *
 *   POST   /chaos/health?status=DOWN|OUT_OF_SERVICE|UNKNOWN|UP&reason=..&seconds=   overall /actuator/health only
 *   POST   /chaos/readiness?refuse=true&seconds=     readiness probe 503: pod leaves the Service endpoints
 *   POST   /chaos/liveness?broken=true&seconds=      liveness probe 503: kubelet restarts the container
 *   POST   /chaos/memory?mb=&mode=heap|direct&allowOom=&seconds=      DELETE /chaos/memory
 *   POST   /chaos/cpu?threads=&seconds=                               DELETE /chaos/cpu
 *   POST   /chaos/latency?ms=&jitter=&actuator=&seconds=              DELETE /chaos/latency
 *   POST   /chaos/errors?percent=&status=&seconds=                    DELETE /chaos/errors
 *   POST   /chaos/disk?mb=&seconds=                                   DELETE /chaos/disk
 *   POST   /chaos/mock?name=&value=          DELETE /chaos/mock?name=   DELETE /chaos/mock (all)
 *   POST   /chaos/reset                      everything off
 *   POST   /chaos/crash?mode=exit|halt&code=1
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {
    private static final String POD = System.getenv().getOrDefault("HOSTNAME", "local");
    private final ChaosService chaos;
    private final String version;

    @Value("${canary.chaos.enabled:false}") boolean enabled;
    @Value("${canary.chaos.token:}") String token;

    public ChaosController(ChaosService chaos, ObjectProvider<BuildProperties> build) {
        this.chaos = chaos;
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled);
        s.put("tokenRequired", !token.isBlank());
        s.put("version", version);
        s.put("pod", POD);
        s.put("node", System.getenv().getOrDefault("NODE_NAME", "unknown"));
        s.put("namespace", System.getenv().getOrDefault("POD_NAMESPACE", "unknown"));
        s.putAll(chaos.state());
        return s;
    }

    @PostMapping("/health")
    public Map<String, Object> health(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                      @RequestParam String status, @RequestParam(defaultValue = "") String reason,
                                      @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        Status s = switch (status.toUpperCase()) {
            case "UP" -> Status.UP;
            case "DOWN" -> Status.DOWN;
            case "OUT_OF_SERVICE" -> Status.OUT_OF_SERVICE;
            case "UNKNOWN" -> Status.UNKNOWN;
            default -> throw new IllegalArgumentException("status must be UP, DOWN, OUT_OF_SERVICE or UNKNOWN");
        };
        chaos.setHealth(s, reason, seconds(seconds));
        return state();
    }

    @PostMapping("/readiness")
    public Map<String, Object> readiness(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                         @RequestParam boolean refuse, @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        chaos.setReadiness(refuse, seconds(seconds));
        return state();
    }

    @PostMapping("/liveness")
    public Map<String, Object> liveness(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                        @RequestParam boolean broken, @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        chaos.setLiveness(broken, seconds(seconds));
        return state();
    }

    @PostMapping("/memory")
    public Map<String, Object> memory(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                      @RequestParam long mb, @RequestParam(defaultValue = "heap") String mode,
                                      @RequestParam(defaultValue = "false") boolean allowOom,
                                      @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        if (!mode.equals("heap") && !mode.equals("direct")) throw new IllegalArgumentException("mode must be heap or direct");
        Map<String, Object> r = new LinkedHashMap<>(chaos.allocate(mb, mode.equals("direct"), allowOom, seconds(seconds)));
        r.put("state", state());
        return r;
    }

    @DeleteMapping("/memory")
    public Map<String, Object> releaseMemory(@RequestHeader(value = "X-Chaos-Token", required = false) String t) {
        guard(t);
        chaos.release();
        return state();
    }

    @PostMapping("/cpu")
    public Map<String, Object> cpu(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                   @RequestParam int threads, @RequestParam(defaultValue = "60") long seconds) {
        guard(t);
        chaos.burnCpu(threads, seconds(seconds));
        return state();
    }

    @DeleteMapping("/cpu")
    public Map<String, Object> stopCpu(@RequestHeader(value = "X-Chaos-Token", required = false) String t) {
        guard(t);
        chaos.stopCpu();
        return state();
    }

    @PostMapping("/latency")
    public Map<String, Object> latency(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                       @RequestParam long ms, @RequestParam(defaultValue = "0") long jitter,
                                       @RequestParam(defaultValue = "false") boolean actuator,
                                       @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        chaos.setLatency(ms, jitter, actuator, seconds(seconds));
        return state();
    }

    @DeleteMapping("/latency")
    public Map<String, Object> clearLatency(@RequestHeader(value = "X-Chaos-Token", required = false) String t) {
        guard(t);
        chaos.setLatency(0, 0, false, 0);
        return state();
    }

    @PostMapping("/errors")
    public Map<String, Object> errors(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                      @RequestParam int percent, @RequestParam(defaultValue = "500") int status,
                                      @RequestParam(defaultValue = "0") long seconds) {
        guard(t);
        chaos.setErrors(percent, status, seconds(seconds));
        return state();
    }

    @DeleteMapping("/errors")
    public Map<String, Object> clearErrors(@RequestHeader(value = "X-Chaos-Token", required = false) String t) {
        guard(t);
        chaos.setErrors(0, 500, 0);
        return state();
    }

    @PostMapping("/disk")
    public Map<String, Object> disk(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                    @RequestParam long mb, @RequestParam(defaultValue = "0") long seconds) throws IOException {
        guard(t);
        Map<String, Object> r = new LinkedHashMap<>(chaos.fillDisk(mb, seconds(seconds)));
        r.put("state", state());
        return r;
    }

    @DeleteMapping("/disk")
    public Map<String, Object> releaseDisk(@RequestHeader(value = "X-Chaos-Token", required = false) String t) throws IOException {
        guard(t);
        chaos.releaseDisk();
        return state();
    }

    @PostMapping("/mock")
    public Map<String, Object> mock(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                    @RequestParam String name, @RequestParam double value) {
        guard(t);
        chaos.setMock(name, value);
        return state();
    }

    @DeleteMapping("/mock")
    public Map<String, Object> clearMock(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                         @RequestParam(required = false) String name) {
        guard(t);
        if (name == null || name.isBlank()) chaos.clearMocks(); else chaos.clearMock(name);
        return state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestHeader(value = "X-Chaos-Token", required = false) String t) {
        guard(t);
        chaos.resetAll();
        return state();
    }

    @PostMapping("/crash")
    public Map<String, Object> crash(@RequestHeader(value = "X-Chaos-Token", required = false) String t,
                                     @RequestParam(defaultValue = "exit") String mode, @RequestParam(defaultValue = "1") int code) {
        guard(t);
        if (!mode.equals("exit") && !mode.equals("halt")) throw new IllegalArgumentException("mode must be exit or halt");
        chaos.crash(mode.equals("halt"), code);
        return Map.of("pod", POD, "crash", mode, "code", code, "in", "500 ms");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private void guard(String given) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chaos is disabled (canary.chaos.enabled=false)");
        if (!token.isBlank() && (given == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing or wrong X-Chaos-Token");
        }
    }

    private static long seconds(long s) {
        if (s < 0 || s > 86_400) throw new IllegalArgumentException("seconds must be 0..86400");
        return s;
    }
}
