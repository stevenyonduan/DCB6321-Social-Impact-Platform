package edu.gatech.sip6321.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before MVC buffering, and prevent caching even when binding fails. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class SignupRequestFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16384;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals(request.getContextPath() + "/api/auth/signup")
                && !path.equals(request.getContextPath() + "/api/auth/login");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST, OPTIONS");
            error(
                    response,
                    405,
                    "METHOD_NOT_ALLOWED",
                    request.getRequestURI().endsWith("/login")
                            ? "Use POST for login."
                            : "Use POST for signup.");
            return;
        }
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(
                            MediaType.parseMediaType(request.getContentType()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
            return;
        }
        if (request.getContentLengthLong() > LIMIT) {
            tooLarge(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(LIMIT + 1);
        if (body.length > LIMIT) {
            tooLarge(request, response);
            return;
        }
        var input = new ByteArrayInputStream(body);
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return input.read();
                            }

                            @Override
                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException("Synchronous signup only");
                            }
                        };
                    }
                },
                response);
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        error(
                response,
                413,
                "REQUEST_TOO_LARGE",
                request.getRequestURI().endsWith("/login")
                        ? "Login request is too large."
                        : "Signup request is too large.");
    }

    private void error(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // All arguments are fixed application literals, never request/provider values.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
