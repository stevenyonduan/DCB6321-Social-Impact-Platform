package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.social.ValidatedImage;
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

/** Apply profile request size and body rules before MVC deserialization. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ProfileRequestFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16384;
    private static final PathPattern PROFILE_PATH =
            PathPatternParser.defaultInstance.parse("/api/users/me");
    private static final PathPattern AVATAR_PATH =
            PathPatternParser.defaultInstance.parse("/api/users/me/avatar");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        return !(PROFILE_PATH.matches(path) || AVATAR_PATH.matches(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (AVATAR_PATH.matches(
                ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication())) {
            avatarRequest(request, response, chain);
            return;
        }
        if (!"PATCH".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (!accepts(request, MediaType.APPLICATION_JSON)) {
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

    private void avatarRequest(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("PUT".equals(request.getMethod())) {
            if (!accepts(request, MediaType.MULTIPART_FORM_DATA)) {
                error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use multipart/form-data.");
                return;
            }
            if (request.getContentLengthLong() > ValidatedImage.MAX_BYTES + 65536) {
                avatarTooLarge(response);
                return;
            }
            try {
                // Bound both regular and chunked uploads through the servlet's multipart limits.
                request.getParts();
            } catch (IllegalStateException e) {
                avatarTooLarge(response);
                return;
            } catch (ServletException e) {
                error(response, 400, "INVALID_REQUEST", "Send exactly one image file.");
                return;
            }
        } else if ("DELETE".equals(request.getMethod())
                && (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1)) {
            error(response, 400, "INVALID_REQUEST", "Send the request without a body.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean accepts(HttpServletRequest request, MediaType type) {
        try {
            return request.getContentType() != null
                    && type.isCompatibleWith(MediaType.parseMediaType(request.getContentType()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void avatarTooLarge(HttpServletResponse response) throws IOException {
        error(response, 413, "REQUEST_TOO_LARGE", "Profile photo exceeds the 5 MiB limit.");
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
