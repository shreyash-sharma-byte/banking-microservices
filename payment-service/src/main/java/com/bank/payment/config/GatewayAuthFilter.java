package com.bank.payment.config;

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
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (allowedPaths.contains(req.getRequestURI())) { chain.doFilter(request, response); return; }
        if (!gatewaySecret.equals(req.getHeader("X-Gateway-Secret"))) {
            ((HttpServletResponse) response).setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Direct access not allowed\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
