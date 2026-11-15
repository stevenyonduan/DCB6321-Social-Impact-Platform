package edu.gatech.sip6321.organization;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal organization record creation. The calling service handles account assignment. */
@Service
@Profile({"database", "prod", "render"})
public class OrganizationProvisioningService {
    private final OrganizationRepository organizations;

    public OrganizationProvisioningService(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    @Transactional
    public OrganizationResponse create(String name, String description, String website) {
        return OrganizationResponse.of(
                organizations.saveAndFlush(Organization.create(name, description, website)));
    }
}
