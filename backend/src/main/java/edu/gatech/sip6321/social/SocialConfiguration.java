package edu.gatech.sip6321.social;

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
        String bucket = env.getProperty("SIP_STORAGE_BUCKET", "");
        if (!bucket.matches("[a-zA-Z0-9_-]{1,100}")) {
            throw new IllegalStateException("Storage requires a valid SIP_STORAGE_BUCKET.");
        }
        return new SupabasePostImageStorage(SupabaseImageObjects.configured(env), bucket);
    }
}
