package com.ailabs.devops.admin;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.ailabs.devops.config.DevopsProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards {@code /admin}. Every API call needs the {@code X-Devops-Admin} header (a cross-site page
 * cannot set it, so this blocks CSRF), and every response gets strict security headers.
 *
 * <ul>
 *   <li>{@code auth: none}: no sign-in, so only loopback clients are accepted whatever
 *       {@code allowed-networks} says. Remote users come in through an SSH tunnel.</li>
 *   <li>{@code auth: password}: the client IP must be in {@code allowed-networks} and API calls
 *       need a session cookie from {@code /admin/api/login}.</li>
 *   <li>{@code auth: saml}: reserved; the agent refuses to start until it is implemented.</li>
 * </ul>
 */
@Component
@Profile("admin")
@Order(0)
public class AdminSecurityFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Devops-Admin";

    static final List<Cidr> LOOPBACK = List.of(Cidr.parse("127.0.0.0/8"), Cidr.parse("::1/128"));
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityFilter.class);

    private final List<Cidr> allowed;
    private final AdminSessions sessions;
    private final boolean passwordless;

    public AdminSecurityFilter(DevopsProperties props, AdminSessions sessions) {
        DevopsProperties.Admin admin = props.admin();
        if ("saml".equals(admin.auth())) {
            throw new IllegalStateException("devops.admin.auth=saml is not implemented yet; use none or password");
        }
        this.passwordless = admin.passwordless();
        this.sessions = sessions;
        if (passwordless) {
            this.allowed = LOOPBACK;
            log.warn("Admin UI has no sign-in (devops.admin.auth=none): only clients on this machine are accepted; "
                    + "use an SSH tunnel for remote access");
        } else {
            this.allowed = admin.allowedNetworks().stream().map(Cidr::parse).toList();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !(p.equals("/admin") || p.startsWith("/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'");

        if (!Cidr.anyContains(allowed, req.getRemoteAddr())) {
            deny(res, HttpServletResponse.SC_FORBIDDEN, "address not allowed");
            return;
        }
        String path = req.getRequestURI();
        if (path.startsWith("/admin/api/")) {
            if (!"1".equals(req.getHeader(CSRF_HEADER))) {
                deny(res, HttpServletResponse.SC_FORBIDDEN, "missing " + CSRF_HEADER + " header");
                return;
            }
            boolean open = passwordless || path.equals("/admin/api/login") || path.equals("/admin/api/session");
            if (!open && !sessions.valid(sessionOf(req))) {
                deny(res, HttpServletResponse.SC_UNAUTHORIZED, "login required");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    public static String sessionOf(HttpServletRequest req) {
        Cookie c = WebUtils.getCookie(req, AdminSessions.COOKIE);
        return c == null ? null : c.getValue();
    }

    private static void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
