package com.ailabs.vmadmin.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.secrets.SecretResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /mcp} needs {@code Authorization: Bearer <token>} matching {@code vmadmin.mcp.token-ref}.
 * Without a configured token MCP is switched off (503), since it can run commands on every VM.
 */
@Component
@Order(0)
public class McpAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    private final byte[] expected;

    public McpAuthFilter(AdminProperties props) {
        String t = SecretResolver.bootstrap(props.mcp().tokenRef(), "vmadmin.mcp.token-ref");
        if (t != null && t.length() < 24) {
            throw new IllegalStateException("vmadmin.mcp.token-ref must point to a token of at least 24 characters");
        }
        this.expected = t == null ? null : t.getBytes(StandardCharsets.UTF_8);
        log.info(t == null ? "MCP endpoint disabled (vmadmin.mcp.token-ref not set)" : "MCP endpoint enabled at /mcp (bearer token)");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            UiSecurityFilter.deny(res, 503, "MCP is disabled; set vmadmin.mcp.token-ref");
            return;
        }
        String h = req.getHeader("Authorization");
        byte[] got = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)
                ? h.substring(7).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(expected, got)) {
            res.setHeader("WWW-Authenticate", "Bearer");
            UiSecurityFilter.deny(res, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }
        Audit.actor("mcp@" + req.getRemoteAddr());
        try {
            chain.doFilter(req, res);
        } finally {
            Audit.clear();
        }
    }
}
