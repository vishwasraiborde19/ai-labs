package com.ailabs.vmadmin.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.config.AdminProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the UI ({@code /} and {@code /ui/**}).
 *
 * <ul>
 *   <li>{@code auth: none}: no sign-in, so only clients on this machine are served. Reach it from
 *       elsewhere through an SSH tunnel.</li>
 *   <li>{@code auth: saml}: reserved for app-level SAML; the control centre refuses to start until it exists.</li>
 * </ul>
 * Every API call needs the {@code X-VM-Admin: 1} header, which a cross-site page cannot set (CSRF),
 * and every response gets strict security headers.
 */
@Component
@Order(0)
public class UiSecurityFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-VM-Admin";
    private static final Logger log = LoggerFactory.getLogger(UiSecurityFilter.class);

    public UiSecurityFilter(AdminProperties props) {
        if ("saml".equals(props.ui().auth())) {
            throw new IllegalStateException("vmadmin.ui.auth=saml is not implemented yet; use none");
        }
        log.warn("UI has no sign-in (vmadmin.ui.auth=none): only clients on this machine are accepted; "
                + "use an SSH tunnel for remote access");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/mcp");
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

        if (!loopback(req.getRemoteAddr())) {
            deny(res, HttpServletResponse.SC_FORBIDDEN, "the control centre only accepts connections from this machine (use an SSH tunnel)");
            return;
        }
        if (req.getRequestURI().startsWith("/ui/api/") && !"1".equals(req.getHeader(CSRF_HEADER))) {
            deny(res, HttpServletResponse.SC_FORBIDDEN, "missing " + CSRF_HEADER + " header");
            return;
        }
        Audit.actor("ui@" + req.getRemoteAddr());
        try {
            chain.doFilter(req, res);
        } finally {
            Audit.clear();
        }
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
        res.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}
