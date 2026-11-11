package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.UserRole;
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

    public OrganizationService(ProfileService profiles, OrganizationRepository organizations) {
        this.profiles = profiles;
        this.organizations = organizations;
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
