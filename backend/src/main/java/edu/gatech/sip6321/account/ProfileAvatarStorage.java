package edu.gatech.sip6321.account;

import edu.gatech.sip6321.social.SocialFailure;
import edu.gatech.sip6321.social.SupabaseImageObjects;
import edu.gatech.sip6321.social.ValidatedImage;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
class ProfileAvatarStorage implements AutoCloseable {
    private static final String OBJECT_NAME =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png)";
    private final String bucket;
    private final Optional<SupabaseImageObjects> objects;

    ProfileAvatarStorage(Environment env) {
        bucket = env.getProperty("SIP_AVATAR_STORAGE_BUCKET", "profile-avatars");
        boolean enabled = env.getProperty("SIP_STORAGE_ENABLED", Boolean.class, false);
        if (enabled
                && (!bucket.matches("[a-zA-Z0-9_-]{1,100}")
                        || bucket.equals(env.getProperty("SIP_STORAGE_BUCKET", "")))) {
            throw new IllegalStateException(
                    "SIP_AVATAR_STORAGE_BUCKET must name a separate private avatar bucket.");
        }
        objects = enabled ? Optional.of(SupabaseImageObjects.configured(env)) : Optional.empty();
    }

    String upload(UUID user, ValidatedImage image) {
        String reference = prefix(user) + UUID.randomUUID() + "." + image.extension();
        objects.orElseThrow(SocialFailure::storage).upload(reference, image);
        return reference;
    }

    ProfileAvatarService.Photo download(UUID user, String reference) {
        checkOwner(user, reference);
        String mediaType = reference.endsWith(".png") ? "image/png" : "image/jpeg";
        return new ProfileAvatarService.Photo(
                objects.orElseThrow(SocialFailure::storage).download(reference, mediaType),
                mediaType);
    }

    void delete(UUID user, String reference) {
        checkOwner(user, reference);
        objects.orElseThrow(SocialFailure::storage).delete(reference);
    }

    boolean owns(UUID user, String reference) {
        return reference != null && reference.matches(Pattern.quote(prefix(user)) + OBJECT_NAME);
    }

    private void checkOwner(UUID user, String reference) {
        if (!owns(user, reference)) throw SocialFailure.storage();
    }

    private String prefix(UUID user) {
        return bucket + "/avatars/" + user + "/";
    }

    @Override
    @PreDestroy
    public void close() {
        objects.ifPresent(SupabaseImageObjects::close);
    }
}
