package com.bank.transaction.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;

@Component
public class GatewayAuthFilter implements Filter {
    private final String gatewaySecret;
    private final List<String> allowedPaths = List.of("/actuator/health", "/actuator/info");

    public GatewayAuthFilter(@Value("${app.gateway.secret}") String gatewaySecret) {
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (allowedPaths.contains(((HttpServletRequest) req).getRequestURI())) { chain.doFilter(req, res); return; }
        if (!gatewaySecret.equals(((HttpServletRequest) req).getHeader("X-Gateway-Secret"))) {
            ((HttpServletResponse) res).setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Direct access not allowed\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
