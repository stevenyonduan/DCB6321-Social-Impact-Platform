package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.UserService;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class SignupConfiguration {
    @Bean(destroyMethod = "close")
    HttpClient signupHttpClient(Environment env) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout(env, "SIP_AUTH_CONNECT_TIMEOUT_MS", 3000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    SupabaseAuthClient supabaseAuthClient(
            HttpClient signupHttpClient, ObjectMapper json, Validator validator, Environment env) {
        URI url;
        try {
            url = URI.create(env.getProperty("SUPABASE_URL", ""));
            if (!"https".equals(url.getScheme())
                    || url.getHost() == null
                    || url.getRawUserInfo() != null
                    || url.getRawQuery() != null
                    || url.getRawFragment() != null
                    || !(url.getRawPath().isEmpty() || "/".equals(url.getRawPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("SUPABASE_URL must be an HTTPS project origin");
        }
        String key = env.getProperty("SUPABASE_PUBLISHABLE_KEY", "");
        if (!key.matches("sb_publishable_[A-Za-z0-9_-]+")) {
            throw new IllegalStateException(
                    "SUPABASE_PUBLISHABLE_KEY must be a modern publishable key");
        }
        return new HttpSupabaseAuthClient(
                signupHttpClient,
                json,
                validator,
                url,
                key,
                timeout(env, "SIP_AUTH_REQUEST_TIMEOUT_MS", 10000));
    }

    @Bean
    SignupService signupService(SupabaseAuthClient auth, UserService users) {
        return new SignupService(auth, users);
    }

    @Bean
    LoginService loginService(SupabaseAuthClient auth, UserService users) {
        return new LoginService(auth, users);
    }

    private Duration timeout(Environment env, String name, int fallback) {
        try {
            int milliseconds = Integer.parseInt(env.getProperty(name, Integer.toString(fallback)));
            if (milliseconds < 1 || milliseconds > 30000) {
                throw new IllegalArgumentException();
            }
            return Duration.ofMillis(milliseconds);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be between 1 and 30000 milliseconds");
        }
    }
}
