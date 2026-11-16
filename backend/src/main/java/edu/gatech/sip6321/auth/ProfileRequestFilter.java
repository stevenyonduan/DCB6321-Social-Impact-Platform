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
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Bound PATCH buffering, including bodies without Content-Length, before MVC parses JSON. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ProfileRequestFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16384;
    private static final PathPattern PROFILE_PATH =
            PathPatternParser.defaultInstance.parse("/api/users/me");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        return !PROFILE_PATH.matches(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!"PATCH".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(
                            MediaType.parseMediaType(request.getContentType())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
            return;
        }
        if (request.getContentLengthLong() > LIMIT) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(LIMIT + 1);
        if (body.length > LIMIT) {
            tooLarge(response);
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
                                throw new UnsupportedOperationException(
                                        "Synchronous profile update only");
                            }
                        };
                    }
                },
                response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        error(response, 413, "REQUEST_TOO_LARGE", "Profile update request is too large.");
    }

    private void error(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // Fixed application literals only, never request values or exception messages.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
