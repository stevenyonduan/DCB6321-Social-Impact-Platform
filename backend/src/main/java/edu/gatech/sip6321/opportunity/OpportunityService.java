package edu.gatech.sip6321.opportunity;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.auth.ProfileService;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
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
    private final Clock clock;

    public OpportunityService(
            ProfileService profiles,
            OrganizationRepository organizations,
            EventRepository events,
            Clock clock) {
        this.profiles = profiles;
        this.organizations = organizations;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public OpportunityResponse publish(
            List<String> authorization, PublishOpportunityRequest request) {
        var account = profiles.me(authorization);
        if (account.role() != UserRole.ORGANIZATION || account.organizationId() == null)
            throw OpportunityFailure.forbidden();
        requireOrganization(account.organizationId());
        if (request == null) throw OpportunityFailure.invalid();
        // Compare the stored-precision start to the full-precision server clock.
        if (!request.eventStart().isAfter(clock.instant()))
            throw OpportunityFailure.startNotFuture();
        return events.saveAndFlush(Event.create(account.organizationId(), account.id(), request))
                .response();
    }

    public OpportunityPage list(
            List<String> authorization, UUID organizationId, int page, int size) {
        profiles.me(authorization);
        if (organizationId == null || page < 0 || page > 10000 || size < 1 || size > 100)
            throw OpportunityFailure.invalid();
        requireOrganization(organizationId);
        // One cutoff for the entire SQL Slice; flooring avoids JDBC rounding to the next
        // microsecond.
        var cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result =
                events.findByOrganizationIdAndEventStartAfterOrderByEventStartAscIdAsc(
                        organizationId, cutoff, PageRequest.of(page, size));
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
