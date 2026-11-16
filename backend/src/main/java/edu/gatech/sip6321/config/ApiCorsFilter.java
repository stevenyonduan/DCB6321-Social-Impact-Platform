package edu.gatech.sip6321.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiCorsFilter extends CorsFilter {
    public ApiCorsFilter(@Value("${SIP_CORS_ALLOWED_ORIGINS:}") String configuredOrigins) {
        super(corsSource(configuredOrigins));
    }

    private static UrlBasedCorsConfigurationSource corsSource(String configuredOrigins) {
        var source = new UrlBasedCorsConfigurationSource();
        if (configuredOrigins.isBlank()) return source;
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(parseOrigins(configuredOrigins));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static List<String> parseOrigins(String configuredOrigins) {
        var origins = new ArrayList<String>();
        for (String value : configuredOrigins.split(",", -1)) {
            String origin = value.trim();
            try {
                URI uri = URI.create(origin);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawAuthority().contains("*")
                        || uri.getRawUserInfo() != null
                        || !uri.getRawPath().isEmpty()
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "SIP_CORS_ALLOWED_ORIGINS must contain comma-separated exact HTTP(S)"
                                + " origins without paths, wildcards, queries or credentials");
            }
            origins.add(origin);
        }
        return origins;
    }
}
