package edu.gatech.sip6321.opportunity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Route-scoped errors for failures that occur before MVC selects a controller/advice. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OpportunityRequestFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).matches("/api/organizations/[^/]+/opportunities");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String method = request.getMethod();
        if ("OPTIONS".equals(method)) {
            chain.doFilter(request, response);
            return;
        }
        boolean publishing = path(request).equals("/api/organizations/me/opportunities");
        if (publishing
                ? !"POST".equals(method)
                : !("GET".equals(method) || "HEAD".equals(method))) {
            response.setHeader("Allow", publishing ? "POST, OPTIONS" : "GET, HEAD, OPTIONS");
            error(response, 405, "METHOD_NOT_ALLOWED", "Use a supported opportunity method.");
            return;
        }
        if (publishing) {
            try {
                if (request.getContentType() == null
                        || !MediaType.APPLICATION_JSON.isCompatibleWith(
                                MediaType.parseMediaType(request.getContentType())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private void error(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // Fixed literals only; never echo request or exception values.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
