package edu.gatech.sip6321.opportunity;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.auth.ProfileService;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class OpportunityService {
    private final ProfileService profiles;
    private final OrganizationRepository organizations;
    private final EventRepository events;

    public OpportunityService(
            ProfileService profiles, OrganizationRepository organizations, EventRepository events) {
        this.profiles = profiles;
        this.organizations = organizations;
        this.events = events;
    }

    @Transactional
    public OpportunityResponse publish(
            List<String> authorization, PublishOpportunityRequest request) {
        var account = profiles.me(authorization);
        if (account.role() != UserRole.ORGANIZATION || account.organizationId() == null)
            throw OpportunityFailure.forbidden();
        requireOrganization(account.organizationId());
        if (request == null) throw OpportunityFailure.invalid();
        return events.saveAndFlush(Event.create(account.organizationId(), account.id(), request))
                .response();
    }

    public OpportunityPage list(
            List<String> authorization, UUID organizationId, int page, int size) {
        profiles.me(authorization);
        if (organizationId == null || page < 0 || page > 10000 || size < 1 || size > 100)
            throw OpportunityFailure.invalid();
        requireOrganization(organizationId);
        var result =
                events.findByOrganizationIdOrderByEventStartAscIdAsc(
                        organizationId, PageRequest.of(page, size));
        return new OpportunityPage(
                result.getContent().stream().map(Event::response).toList(),
                page,
                size,
                result.hasNext());
    }

    private void requireOrganization(UUID id) {
        if (!organizations.existsById(id)) throw OpportunityFailure.missing();
    }
}
