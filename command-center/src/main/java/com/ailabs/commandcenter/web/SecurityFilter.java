package com.ailabs.commandcenter.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.commandcenter.Audit;
import com.ailabs.commandcenter.config.CcProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <ul>
 *   <li>API calls need {@code X-Command-Center: 1}, which a cross-site page cannot send (CSRF).</li>
 *   <li>Changes (POST, PUT, DELETE) are accepted only from this machine unless
 *       {@code commandcenter.ui.edit-from-loopback-only: false}; others can browse.</li>
 *   <li>A strict Content-Security-Policy: no inline scripts, no third-party content.</li>
 * </ul>
 */
@Component
@Order(1)
public class SecurityFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Command-Center";

    private final boolean editLocalOnly;

    public SecurityFilter(CcProperties props) {
        this.editLocalOnly = props.ui().editFromLoopbackOnly();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        String path = req.getRequestURI();
        if (path.startsWith("/ui/api/")) {
            if (!"1".equals(req.getHeader(CSRF_HEADER))) {
                deny(res, 403, "missing " + CSRF_HEADER + " header");
                return;
            }
            boolean write = !"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod());
            if (write && editLocalOnly && !loopback(req.getRemoteAddr()) && !path.equals("/ui/api/health/check")) {
                deny(res, 403, "changes are only accepted on the command center's own machine (commandcenter.ui.edit-from-loopback-only)");
                return;
            }
        }
        Audit.actor("ui@" + req.getRemoteAddr());
        try {
            chain.doFilter(req, res);
        } finally {
            Audit.clear();
        }
    }

    public boolean canEdit(String remoteAddr) {
        return !editLocalOnly || loopback(remoteAddr);
    }

    static boolean loopback(String ip) {
        try {
            return ip != null && InetAddress.getByName(ip).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}
