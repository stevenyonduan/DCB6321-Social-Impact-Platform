package edu.gatech.sip6321.account;

import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.auth.ProfileService;
import edu.gatech.sip6321.social.ValidatedImage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ProfileAvatarService {
    private static final Logger LOG = LoggerFactory.getLogger(ProfileAvatarService.class);
    private final ProfileService profiles;
    private final UserService users;
    private final ProfileAvatarStorage storage;

    ProfileAvatarService(ProfileService profiles, UserService users, ProfileAvatarStorage storage) {
        this.profiles = profiles;
        this.users = users;
        this.storage = storage;
    }

    public record Photo(byte[] bytes, String mediaType) {}

    public Photo get(List<String> headers) {
        var profile = profiles.me(headers);
        if (!storage.owns(profile.id(), profile.avatarUrl())) throw ProfileFailure.avatarMissing();
        return storage.download(profile.id(), profile.avatarUrl());
    }

    @Transactional
    public ProfileResponse replace(List<String> headers, MultipartFile file) {
        var id = profiles.verifiedUserId(headers);
        var image = ValidatedImage.read(file);
        var user = lock(id);
        String replacement = storage.upload(id, image);
        changeAvatar(user, replacement);
        return ProfileResponse.of(user);
    }

    @Transactional
    public ProfileResponse remove(List<String> headers) {
        var user = lock(profiles.verifiedUserId(headers));
        if (user.getAvatarUrl() != null) changeAvatar(user, null);
        return ProfileResponse.of(user);
    }

    private User lock(UUID id) {
        try {
            return users.lockById(id);
        } catch (AccountNotFoundException e) {
            throw ProfileFailure.provisioning();
        }
    }

    private void changeAvatar(User user, String replacement) {
        var id = user.getId();
        var previous = user.getAvatarUrl();
        // Never remove the old photo until the new reference or removal has committed.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED && storage.owns(id, previous)) {
                            cleanup(id, previous);
                        } else if (status == STATUS_ROLLED_BACK && replacement != null) {
                            cleanup(id, replacement);
                        } else if (status == STATUS_UNKNOWN) {
                            LOG.warn(
                                    "Profile photo transaction outcome unknown; reconciliation required");
                        }
                    }
                });
        user.replaceAvatar(replacement);
    }

    private void cleanup(UUID id, String reference) {
        try {
            storage.delete(id, reference);
        } catch (RuntimeException ignored) {
            LOG.warn("Profile photo cleanup failed; orphan reconciliation required");
        }
    }
}
