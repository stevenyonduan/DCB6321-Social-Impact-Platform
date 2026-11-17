package edu.gatech.sip6321.social;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON buffering and reject bodies on bodyless mutations, including chunked bodies. */
@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class SocialRequestFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/posts")
                || path.startsWith("/api/posts/")
                || path.equals("/api/users/me/saved-posts"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean mutation =
                java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
        boolean multipart =
                request.getContentType() != null
                        && request.getContentType()
                                .toLowerCase(java.util.Locale.ROOT)
                                .startsWith("multipart/form-data");
        if (multipart) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!(request.getMethod().equals("POST") && path.equals("/api/posts"))
                    && !(request.getMethod().equals("PUT")
                            && path.matches("/api/posts/[^/]+/image"))) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            if (request.getContentLengthLong() > ValidatedImage.MAX_BYTES + 65536) {
                error(response, 413, "REQUEST_TOO_LARGE");
                return;
            }
            try {
                // Parse with the servlet's disk-spooling byte limits before MVC selects a
                // handler, so oversize errors (also chunked uploads) remain sanitized JSON.
                request.getParts();
            } catch (IllegalStateException e) {
                error(response, 413, "REQUEST_TOO_LARGE");
                return;
            } catch (ServletException e) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!mutation) {
            chain.doFilter(request, response);
            return;
        }
        int limit = SocialText.MAX_BYTES;
        if (request.getContentLengthLong() > limit) {
            error(response, 413, "REQUEST_TOO_LARGE");
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            error(response, 413, "REQUEST_TOO_LARGE");
            return;
        }
        String path = request.getRequestURI();
        boolean bodyless =
                request.getMethod().equals("DELETE")
                        || path.endsWith("/like")
                        || path.endsWith("/save");
        if (bodyless && bytes.length > 0) {
            error(response, 400, "INVALID_REQUEST");
            return;
        }
        var input = new ByteArrayInputStream(bytes);
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
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                },
                response);
    }

    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"code\":\"" + code + "\",\"message\":\"Invalid social request.\"}");
    }
}
