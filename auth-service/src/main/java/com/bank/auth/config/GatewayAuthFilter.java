package com.bank.auth.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Rejects requests that don't come through the API Gateway.
 * The Gateway injects X-Gateway-Secret after JWT validation.
 * Direct calls to this service bypassing the Gateway get a 403.
 */
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
        String path = req.getRequestURI();

        // Allow actuator health checks without gateway secret (Docker healthcheck)
        if (allowedPaths.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String secret = req.getHeader("X-Gateway-Secret");
        if (!gatewaySecret.equals(secret)) {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(403);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Direct access not allowed. Use API Gateway.\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
