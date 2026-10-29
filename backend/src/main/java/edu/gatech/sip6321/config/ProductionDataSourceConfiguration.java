package edu.gatech.sip6321.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.postgresql.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@Profile({"prod", "render"})
public class ProductionDataSourceConfiguration {
    @Bean
    HikariDataSource dataSource(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local", "database"))) {
            throw new IllegalStateException("Do not combine deployment and development profiles");
        }
        String url = required(environment, "spring.datasource.url");
        validateTlsUrl(url);
        var datasource = new HikariDataSource();
        datasource.setJdbcUrl(url);
        datasource.setUsername(required(environment, "spring.datasource.username"));
        datasource.setPassword(required(environment, "spring.datasource.password"));
        datasource.setDriverClassName("org.postgresql.Driver");
        datasource.setMaximumPoolSize(3);
        datasource.setMinimumIdle(0);
        datasource.setConnectionTimeout(10000);
        datasource.setValidationTimeout(5000);
        datasource.addDataSourceProperty("connectTimeout", "10");
        return datasource;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required deployment setting is missing: " + name);
        }
        return value;
    }

    static void validateTlsUrl(String url) {
        // Use the driver's parser and disallow custom factories/verifiers or URL credentials.
        try {
            if (!url.startsWith("jdbc:postgresql://") || url.contains("#")) {
                throw new IllegalArgumentException();
            }
            int queryStart = url.indexOf('?');
            if (queryStart < 0) {
                throw new IllegalArgumentException();
            }
            URI endpoint = URI.create(url.substring("jdbc:".length(), queryStart));
            if (endpoint.getHost() == null || endpoint.getRawUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            Set<String> seen = new HashSet<>();
            for (String option : url.substring(queryStart + 1).split("&", -1)) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!Set.of("sslmode", "sslrootcert").contains(key) || !seen.add(key)) {
                    throw new IllegalArgumentException();
                }
            }
            Properties parsed = Driver.parseURL(url, new Properties());
            if (parsed == null
                    || !Set.of("require", "verify-full")
                            .contains(parsed.getProperty("sslmode", ""))) {
                throw new IllegalArgumentException();
            }
            // require enforces encryption without a separate CA or server identity verification.
            // Retain the existing certificate-verifying option for deployments that select it.
            if ("require".equals(parsed.getProperty("sslmode"))) {
                return;
            }
            Path certificate = Path.of(parsed.getProperty("sslrootcert", ""));
            if (!certificate.isAbsolute() || !Files.isRegularFile(certificate)) {
                throw new IllegalArgumentException();
            }
            try (InputStream input = Files.newInputStream(certificate)) {
                if (CertificateFactory.getInstance("X.509").generateCertificates(input).isEmpty()) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (Exception exception) {
            // Neither URL nor parser exception is exposed, since they may contain credentials.
            throw new IllegalStateException(
                    "Production JDBC URL must use sslmode=require or sslmode=verify-full;"
                            + " verify-full also needs sslrootcert pointing to a readable absolute"
                            + " X.509 CA file. Only sslmode and sslrootcert query options are supported");
        }
    }
}
