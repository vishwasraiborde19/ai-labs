package com.ailabs.devops.admin;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ailabs.devops.config.DevopsProperties;

/** In-memory admin sessions and login throttling (5 failures per IP locks it for 5 minutes). */
@Component
@Profile("admin")
public class AdminSessions {

    public static final String COOKIE = "devops_admin";
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    private record Failures(int count, Instant lockedUntil) {}

    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AdminSessions(DevopsProperties props) {
        this.ttl = props.admin().sessionTtl();
    }

    public Duration ttl() {
        return ttl;
    }

    public String create() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, Instant.now().plus(ttl));
        sessions.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
        return token;
    }

    public boolean valid(String token) {
        if (token == null) {
            return false;
        }
        Instant exp = sessions.get(token);
        if (exp == null || exp.isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void end(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void endAll() {
        sessions.clear();
    }

    public boolean locked(String ip) {
        Failures f = failures.get(ip);
        return f != null && f.lockedUntil() != null && f.lockedUntil().isAfter(Instant.now());
    }

    public void failed(String ip) {
        failures.compute(ip, (k, f) -> {
            int n = (f == null || (f.lockedUntil() != null && f.lockedUntil().isBefore(Instant.now()))) ? 1 : f.count() + 1;
            return new Failures(n, n >= MAX_FAILURES ? Instant.now().plus(LOCKOUT) : null);
        });
    }

    public void succeeded(String ip) {
        failures.remove(ip);
    }
}
