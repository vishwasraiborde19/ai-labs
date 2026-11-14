package com.ailabs.canary.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the latency and error faults to incoming requests.
 *
 * Never touched: the console itself (/, /index.html, /chaos/**), so a fault can always be switched off.
 * /actuator/** gets latency only when "actuator too" is ticked (slow probes: kubelet's timeout is 1s),
 * and never gets injected errors (use the health / readiness / liveness faults for that).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ChaosFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);
    private final ChaosService chaos;

    public ChaosFilter(ChaosService chaos) {
        this.chaos = chaos;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI().substring(req.getContextPath().length());
        return p.equals("/") || p.equals("/index.html") || p.equals("/favicon.ico") || p.startsWith("/chaos");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        boolean actuator = path.startsWith("/actuator");
        long delay = chaos.delayFor(actuator);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int status = actuator ? 0 : chaos.errorFor();
        if (status > 0) {
            log.warn("CHAOS injected {} for {} {}", status, req.getMethod(), path);
            res.setStatus(status);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"chaos\",\"status\":" + status + ",\"path\":\"" + path.replace("\"", "") + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
