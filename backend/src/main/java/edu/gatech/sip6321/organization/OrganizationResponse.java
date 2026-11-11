package edu.gatech.sip6321.organization;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String description,
        String website,
        boolean isVerified,
        Instant createdAt) {
    static OrganizationResponse of(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getDescription(),
                organization.getWebsite(),
                organization.isVerified(),
                organization.getCreatedAt());
    }
}
