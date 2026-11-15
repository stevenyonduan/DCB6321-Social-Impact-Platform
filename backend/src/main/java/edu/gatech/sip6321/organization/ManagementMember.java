package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.OrganizationRole;
import edu.gatech.sip6321.account.User;
import java.util.UUID;

public record ManagementMember(
        UUID id, String firstName, String lastName, OrganizationRole organizationRole) {
    static ManagementMember of(User user) {
        return new ManagementMember(
                user.getId(), user.getFirstName(), user.getLastName(), user.getOrganizationRole());
    }
}
