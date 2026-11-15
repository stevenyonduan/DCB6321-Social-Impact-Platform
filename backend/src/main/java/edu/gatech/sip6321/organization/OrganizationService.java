package edu.gatech.sip6321.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class OrganizationService {
    private final OrganizationAuthorization authorization;
    private final OrganizationRepository organizations;

    public OrganizationService(
            OrganizationAuthorization authorization, OrganizationRepository organizations) {
        this.authorization = authorization;
        this.organizations = organizations;
    }

    public OrganizationResponse get(List<String> authorization, UUID id) {
        this.authorization.account(authorization);
        return OrganizationResponse.of(find(id));
    }

    public OrganizationResponse me(List<String> authorization) {
        var account = this.authorization.manager(authorization);
        return OrganizationResponse.of(find(account.organizationId()));
    }

    @Transactional
    public OrganizationResponse patch(
            List<String> authorization, UUID id, OrganizationPatch patch) {
        this.authorization.manager(authorization, id);
        if (patch == null) throw OrganizationFailure.invalid();
        var organization = find(id);
        organization.apply(patch);
        organizations.flush();
        return OrganizationResponse.of(organization);
    }

    private Organization find(UUID id) {
        return organizations.findById(id).orElseThrow(OrganizationFailure::missing);
    }
}
