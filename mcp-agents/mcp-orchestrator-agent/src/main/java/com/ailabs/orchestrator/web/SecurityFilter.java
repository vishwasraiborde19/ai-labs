package com.ailabs.orchestrator.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.store.Secrets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <ul>
 *   <li>{@code /registry/**} (agents registering themselves): bearer token from
 *       {@code orchestrator.registration.token-ref}, from any address the server listens on; 404 when not set.</li>
 *   <li>everything else (UI and {@code /ui/api}): this machine only, no sign-in; use an SSH tunnel from elsewhere.
 *       API calls need {@code X-Orchestrator: 1}, which a cross-site page cannot send (CSRF).</li>
 * </ul>
 */
@Component
@Order(0)
public class SecurityFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Orchestrator";

    private final OrchestratorProperties props;
    private final Secrets secrets;

    public SecurityFilter(OrchestratorProperties props, Secrets secrets) {
        this.props = props;
        this.secrets = secrets;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'");

        String path = req.getRequestURI();
        if (path.startsWith("/registry/")) {
            if (!props.registration().enabled()) {
                deny(res, 404, "self-registration is not enabled (set orchestrator.registration.token-ref)");
                return;
            }
            String expected;
            try {
                expected = "Bearer " + secrets.resolve(props.registration().tokenRef());
            } catch (RuntimeException e) {
                deny(res, 503, "registration token is not available: " + e.getMessage());
                return;
            }
            String got = req.getHeader("Authorization");
            if (got == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8))) {
                deny(res, 401, "unauthorized");
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (!loopback(req.getRemoteAddr())) {
            deny(res, 403, "the orchestrator UI only accepts connections from this machine (use an SSH tunnel)");
            return;
        }
        if (path.startsWith("/ui/api/") && !"1".equals(req.getHeader(CSRF_HEADER))) {
            deny(res, 403, "missing " + CSRF_HEADER + " header");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean loopback(String ip) {
        try {
            return ip != null && InetAddress.getByName(ip).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg.replace("\\", "/").replace("\"", "'") + "\"}");
    }
}
