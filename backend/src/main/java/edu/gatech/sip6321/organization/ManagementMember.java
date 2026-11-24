package edu.gatech.sip6321.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.gatech.sip6321.account.OrganizationRole;
import edu.gatech.sip6321.account.User;
import java.util.UUID;

public record ManagementMember(
        UUID id,
        String firstName,
        String lastName,
        OrganizationRole organizationRole,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) String avatarUrl) {
    static ManagementMember of(User user, boolean ownerView) {
        boolean includeAdminDetails =
                ownerView && user.getOrganizationRole() == OrganizationRole.ADMIN;
        return new ManagementMember(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getOrganizationRole(),
                includeAdminDetails ? user.getEmail() : null,
                includeAdminDetails ? user.getAvatarUrl() : null);
    }
}
