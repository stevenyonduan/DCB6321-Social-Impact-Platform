package edu.gatech.sip6321.registration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Consistent private caching and method errors, including before MVC selects a controller. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class RegistrationRequestFilter extends OncePerRequestFilter {
    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request)
                .matches(
                        "/api/opportunities/[^/]+/registrations/me|/api/users/me/registrations|/api/organizations/me/opportunities/[^/]+/registrations");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean personal = path(request).startsWith("/api/opportunities/");
        String method = request.getMethod();
        if (!("GET".equals(method)
                || "HEAD".equals(method)
                || "OPTIONS".equals(method)
                || (personal && ("POST".equals(method) || "DELETE".equals(method))))) {
            response.setStatus(405);
            response.setHeader(
                    "Allow", personal ? "GET, HEAD, POST, DELETE, OPTIONS" : "GET, HEAD, OPTIONS");
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Use a supported registration method.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
