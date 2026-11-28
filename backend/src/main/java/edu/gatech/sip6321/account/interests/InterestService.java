package edu.gatech.sip6321.account.interests;

import static edu.gatech.sip6321.account.interests.InterestRepository.Scope.*;

import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.organization.OrganizationAuthorization;
import edu.gatech.sip6321.organization.OrganizationFailure;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
class InterestService {
    private final OrganizationAuthorization authorization;
    private final InterestRepository interests;
    private final OrganizationRepository organizations;

    InterestService(
            OrganizationAuthorization authorization,
            InterestRepository interests,
            OrganizationRepository organizations) {
        this.authorization = authorization;
        this.interests = interests;
        this.organizations = organizations;
    }

    public List<InterestOption> catalog(List<String> headers) {
        authorization.account(headers);
        return interests.catalog();
    }

    public List<InterestOption> userSelections(List<String> headers) {
        return interests.selections(USER, authorization.account(headers).id());
    }

    public List<InterestOption> organizationSelections(List<String> headers) {
        return interests.selections(ORGANIZATION, authorization.manager(headers).organizationId());
    }

    public List<InterestOption> organizationInterests(List<String> headers, UUID organizationId) {
        authorization.account(headers);
        if (!organizations.existsById(organizationId)) throw OrganizationFailure.missing();
        return interests.selections(ORGANIZATION, organizationId);
    }

    @Transactional
    public List<InterestOption> replaceUserSelections(
            List<String> headers, InterestSelection selection) {
        UUID userId = authorization.account(headers).id();
        if (!interests.lockOwner(USER, userId)) throw ProfileFailure.provisioning();
        return replace(USER, userId, selection);
    }

    @Transactional
    public List<InterestOption> replaceOrganizationSelections(
            List<String> headers, InterestSelection selection) {
        var manager = authorization.manager(headers);
        UUID organizationId = manager.organizationId();
        interests.lockManager(manager.id(), organizationId);
        if (!interests.lockOwner(ORGANIZATION, organizationId)) throw OrganizationFailure.missing();
        return replace(ORGANIZATION, organizationId, selection);
    }

    private List<InterestOption> replace(
            InterestRepository.Scope scope, UUID ownerId, InterestSelection selection) {
        // Throwing within this transaction rolls back both the delete and any partial insert.
        if (!interests.replace(scope, ownerId, selection.interestIds())) {
            throw new UnknownInterestException();
        }
        return interests.selections(scope, ownerId);
    }
}
