package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.OrganizationRole;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.auth.ProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Always reads current application permissions through the verified Supabase identity. */
@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OrganizationAuthorization {
    private final ProfileService profiles;

    public OrganizationAuthorization(ProfileService profiles) {
        this.profiles = profiles;
    }

    public ProfileResponse account(List<String> headers) {
        return profiles.me(headers);
    }

    public ProfileResponse manager(List<String> headers) {
        var account = account(headers);
        if (account.organizationId() == null
                || (account.organizationRole() != OrganizationRole.OWNER
                        && account.organizationRole() != OrganizationRole.ADMIN))
            throw OrganizationFailure.forbidden();
        return account;
    }

    public ProfileResponse manager(List<String> headers, UUID organizationId) {
        var account = manager(headers);
        if (!account.organizationId().equals(organizationId)) throw OrganizationFailure.forbidden();
        return account;
    }

    public ProfileResponse owner(List<String> headers) {
        var account = manager(headers);
        if (account.organizationRole() != OrganizationRole.OWNER)
            throw OrganizationFailure.forbidden();
        return account;
    }
}
