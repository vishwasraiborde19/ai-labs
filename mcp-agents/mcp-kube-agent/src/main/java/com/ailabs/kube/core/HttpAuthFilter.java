package com.ailabs.kube.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.kube.access.SecretResolver;
import com.ailabs.kube.config.KubeProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * In HTTP mode every request except {@code /healthz} must carry {@code Authorization: Bearer <token>},
 * the token being resolved from {@code kube.http.auth-token-ref}. A missing token is a startup error.
 */
@Component
@ConditionalOnWebApplication
@Order(1)
public class HttpAuthFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public HttpAuthFilter(KubeProperties props, SecretResolver secrets) {
        String ref = props.http().authTokenRef();
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("HTTP mode needs kube.http.auth-token-ref (e.g. env:KUBE_AGENT_TOKEN)");
        }
        String token = secrets.resolve(ref, "http-auth");
        if (token.length() < 24) {
            throw new IllegalStateException("kube.http.auth-token-ref must resolve to at least 24 characters");
        }
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return "/healthz".equals(req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
