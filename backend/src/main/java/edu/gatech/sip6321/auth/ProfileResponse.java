package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserRole;
import java.time.Instant;
import java.util.UUID;

/** Allowlisted application profile; never serializes a persistence entity or session. */
public record ProfileResponse(
        UUID id,
        String email,
        UserRole role,
        String firstName,
        String lastName,
        String bio,
        String avatarUrl,
        UUID organizationId,
        Instant createdAt) {
    public static ProfileResponse of(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getFirstName(),
                user.getLastName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getOrganizationId(),
                user.getCreatedAt());
    }

    @Override
    public String toString() {
        return "ProfileResponse[redacted]";
    }
}
