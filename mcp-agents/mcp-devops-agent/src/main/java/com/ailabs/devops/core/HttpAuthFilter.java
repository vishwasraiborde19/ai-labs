package com.ailabs.devops.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.DevopsProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Whenever the agent runs a web server, every non-admin request (the MCP endpoint) must carry
 * {@code Authorization: Bearer <token>} resolved from {@code devops.http.auth-token-ref}.
 * In the {@code http} profile a missing token is a startup error; in {@code admin}-only mode MCP
 * over HTTP is simply refused.
 */
@Component
@ConditionalOnWebApplication
@Order(1)
public class HttpAuthFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public HttpAuthFilter(DevopsProperties props, SecretResolver secrets, Environment env) {
        String ref = props.http().authTokenRef();
        boolean required = env.acceptsProfiles(Profiles.of("http"));
        if (ref == null || ref.isBlank()) {
            if (required) {
                throw new IllegalStateException("HTTP mode needs devops.http.auth-token-ref (e.g. env:DEVOPS_AGENT_TOKEN)");
            }
            this.expected = null;
            return;
        }
        String token = secrets.resolve(ref, "http-auth");
        if (token.length() < 24) {
            throw new IllegalStateException("devops.http.auth-token-ref must resolve to at least 24 characters");
        }
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return p.equals("/admin") || p.startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            reject(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP over HTTP is disabled: set devops.http.auth-token-ref");
            return;
        }
        String header = req.getHeader("Authorization");
        if (header == null || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            reject(res, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
