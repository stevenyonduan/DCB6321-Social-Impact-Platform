package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.auth.ProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class OrganizationService {
    private final ProfileService profiles;
    private final OrganizationRepository organizations;
    private final UserService users;
    private final OrganizationProvisioningService provisioning;

    public OrganizationService(
            ProfileService profiles,
            OrganizationRepository organizations,
            UserService users,
            OrganizationProvisioningService provisioning) {
        this.profiles = profiles;
        this.organizations = organizations;
        this.users = users;
        this.provisioning = provisioning;
    }

    @Transactional
    public OrganizationResponse create(List<String> authorization, OrganizationPatch fields) {
        UUID userId = profiles.verifiedUserId(authorization);
        if (fields == null || !fields.contains("name")) throw OrganizationFailure.invalid();
        // Read eligibility under the lock so concurrent attempts see the first committed
        // assignment.
        User user;
        try {
            user = users.lockById(userId);
        } catch (AccountNotFoundException exception) {
            throw ProfileFailure.provisioning();
        }
        if (user.getRole() == UserRole.ADMIN) throw OrganizationFailure.forbidden();
        if (user.getOrganizationId() != null) throw OrganizationFailure.alreadyAssociated();
        if (user.getRole() != UserRole.STANDARD) throw OrganizationFailure.forbidden();
        var created =
                provisioning.create(
                        fields.value("name"), fields.value("description"), fields.value("website"));
        user.assignCreatedOrganization(created.id());
        organizations.flush();
        return created;
    }

    public OrganizationResponse get(List<String> authorization, UUID id) {
        profiles.me(authorization);
        return OrganizationResponse.of(find(id));
    }

    public OrganizationResponse me(List<String> authorization) {
        var account = profiles.me(authorization);
        requireManager(account);
        return OrganizationResponse.of(find(account.organizationId()));
    }

    @Transactional
    public OrganizationResponse patch(
            List<String> authorization, UUID id, OrganizationPatch patch) {
        var account = profiles.me(authorization);
        requireManager(account);
        // Check ownership before existence to avoid disclosing another organization's state.
        if (!account.organizationId().equals(id)) throw OrganizationFailure.forbidden();
        if (patch == null) throw OrganizationFailure.invalid();
        var organization = find(id);
        organization.apply(patch);
        organizations.flush();
        return OrganizationResponse.of(organization);
    }

    private void requireManager(ProfileResponse account) {
        if (account.role() != UserRole.ORGANIZATION || account.organizationId() == null)
            throw OrganizationFailure.forbidden();
    }

    private Organization find(UUID id) {
        return organizations.findById(id).orElseThrow(OrganizationFailure::missing);
    }
}
