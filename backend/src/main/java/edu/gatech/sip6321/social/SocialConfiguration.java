package edu.gatech.sip6321.social;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class SocialConfiguration {
    @Bean
    PostImageStorage postImageStorage(Environment env) {
        if (!env.getProperty("SIP_STORAGE_ENABLED", Boolean.class, false))
            return new PostImageStorage() {
                public String upload(ValidatedImage image) {
                    throw SocialFailure.storage();
                }

                public void delete(String reference) {
                    throw SocialFailure.storage();
                }
            };
        URI origin;
        String bucket = env.getProperty("SIP_STORAGE_BUCKET", "");
        String key = env.getProperty("SUPABASE_STORAGE_SERVICE_ROLE_KEY", "");
        try {
            origin = URI.create(env.getProperty("SUPABASE_URL", ""));
            if (!"https".equals(origin.getScheme())
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    || !bucket.matches("[a-zA-Z0-9_-]{1,100}")
                    || !key.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Storage requires HTTPS SUPABASE_URL, a safe SIP_STORAGE_BUCKET, and a server-only service-role JWT.");
        }
        return new SupabasePostImageStorage(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                origin,
                bucket,
                key);
    }
}
