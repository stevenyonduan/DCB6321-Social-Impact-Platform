package edu.gatech.sip6321.registration;

import edu.gatech.sip6321.opportunity.*;
import edu.gatech.sip6321.organization.OrganizationAuthorization;
import edu.gatech.sip6321.organization.OrganizationFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class EventRegistrationService {
    private final OrganizationAuthorization authorization;
    private final EventRepository events;
    private final EventRegistrationRepository registrations;
    private final Clock clock;

    public EventRegistrationService(
            OrganizationAuthorization authorization,
            EventRepository events,
            EventRegistrationRepository registrations,
            Clock clock) {
        this.authorization = authorization;
        this.events = events;
        this.registrations = registrations;
        this.clock = clock;
    }

    @Transactional
    public EventRegistrationResponse register(List<String> headers, UUID eventId) {
        var account = authorization.account(headers);
        var event = event(eventId);
        if (event.registrationType() != RegistrationType.INTERNAL)
            throw RegistrationFailure.external();
        upcoming(event);
        if (registrations.insertIfAbsent(eventId, account.id()) == 0)
            throw RegistrationFailure.duplicate();
        return registrations.findByEventIdAndUserId(eventId, account.id()).orElseThrow().response();
    }

    public RegistrationStatusResponse status(List<String> headers, UUID eventId) {
        var account = authorization.account(headers);
        event(eventId);
        var row = registrations.findByEventIdAndUserId(eventId, account.id());
        return new RegistrationStatusResponse(
                eventId,
                row.isPresent(),
                row.map(
                                r -> {
                                    var value = r.response();
                                    return new RegistrationStatusResponse.Registration(
                                            value.id(), value.registeredAt());
                                })
                        .orElse(null));
    }

    @Transactional
    public void cancel(List<String> headers, UUID eventId) {
        var account = authorization.account(headers);
        upcoming(event(eventId));
        if (registrations.cancel(eventId, account.id()) == 0)
            throw RegistrationFailure.missingRegistration();
    }

    public RegistrationPage<MyRegistrationResponse> mine(List<String> headers, int page, int size) {
        var account = authorization.account(headers);
        var result = registrations.mine(account.id(), pagination(page, size));
        return new RegistrationPage<>(result.getContent(), page, size, result.hasNext());
    }

    public RegistrationPage<AttendeeResponse> attendees(
            List<String> headers, UUID eventId, int page, int size) {
        var account = authorization.manager(headers);
        var event = event(eventId);
        if (!account.organizationId().equals(event.organizationId()))
            throw OrganizationFailure.forbidden();
        var result = registrations.attendees(eventId, pagination(page, size));
        return new RegistrationPage<>(result.getContent(), page, size, result.hasNext());
    }

    private OpportunityResponse event(UUID eventId) {
        if (eventId == null) throw RegistrationFailure.invalid();
        return events.findById(eventId).orElseThrow(RegistrationFailure::missingEvent).response();
    }

    private void upcoming(OpportunityResponse event) {
        // Compare the persisted microsecond start against the full precision server time.
        if (!event.eventStart().isAfter(clock.instant())) throw RegistrationFailure.started();
    }

    private PageRequest pagination(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw RegistrationFailure.invalid();
        return PageRequest.of(page, size);
    }
}
