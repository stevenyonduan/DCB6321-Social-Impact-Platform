package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.OrganizationRole;
import java.util.UUID;

public record CreatedOrganization(
        OrganizationResponse organization, UUID ownerId, OrganizationRole organizationRole) {}
