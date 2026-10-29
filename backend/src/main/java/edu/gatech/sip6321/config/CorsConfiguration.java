package edu.gatech.sip6321.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class CorsConfiguration implements WebMvcConfigurer {
    private final List<String> origins = new ArrayList<>();

    public CorsConfiguration(@Value("${SIP_CORS_ALLOWED_ORIGINS:}") String configuredOrigins) {
        if (configuredOrigins.isBlank()) {
            return;
        }
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
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!origins.isEmpty()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(origins.toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders("Authorization", "Content-Type", "Accept")
                    .allowCredentials(false)
                    .maxAge(600);
        }
    }
}
