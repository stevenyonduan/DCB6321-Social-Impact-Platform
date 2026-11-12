package edu.gatech.sip6321.opportunity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class OpportunityServiceTest {
    final ProfileService profiles = mock(ProfileService.class);
    final OrganizationRepository organizations = mock(OrganizationRepository.class);
    final EventRepository events = mock(EventRepository.class);
    static final Instant NOW = Instant.parse("2027-01-01T00:00:00.123456789Z");
    final Clock clock = spy(Clock.fixed(NOW, ZoneOffset.UTC));
    final OpportunityService service =
            new OpportunityService(profiles, organizations, events, clock);
    final List<String> headers = List.of("Bearer test-access");
    final UUID org = UUID.randomUUID();

    @Test
    void directCallsRequireAuthentication() {
        when(profiles.me(headers)).thenThrow(ProfileFailure.unauthorized());
        assertThatThrownBy(() -> service.publish(headers, null)).isInstanceOf(ProfileFailure.class);
        assertThatThrownBy(() -> service.list(headers, org, 0, 20))
                .isInstanceOf(ProfileFailure.class);
        verifyNoInteractions(organizations, events);
    }

    @Test
    void directCallsRequirePersistedManagerAssociation() {
        for (UserRole role : UserRole.values())
            for (UUID association : new UUID[] {null, org}) {
                if (role == UserRole.ORGANIZATION && association != null) continue;
                when(profiles.me(headers))
                        .thenReturn(
                                new ProfileResponse(
                                        UUID.randomUUID(),
                                        "a@example.test",
                                        role,
                                        "A",
                                        "B",
                                        null,
                                        null,
                                        association,
                                        null));
                assertThatThrownBy(() -> service.publish(headers, null))
                        .isInstanceOf(OpportunityFailure.class)
                        .extracting("status")
                        .isEqualTo(403);
            }
        verifyNoInteractions(organizations, events);
    }

    @Test
    void directCallsCannotBypassRequestValidation() {
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        " ",
                                        "Description",
                                        null,
                                        Instant.EPOCH,
                                        Instant.EPOCH.plusSeconds(1),
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "Title",
                                        "Description",
                                        null,
                                        Instant.EPOCH,
                                        Instant.EPOCH,
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "bad\uD800",
                                        "Description",
                                        null,
                                        Instant.EPOCH,
                                        Instant.EPOCH.plusSeconds(1),
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
    }

    @Test
    void timestampsCompareAtStoredPrecision() {
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "Title",
                                        "Description",
                                        null,
                                        Instant.EPOCH,
                                        Instant.EPOCH.plusNanos(1),
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
        var request =
                new PublishOpportunityRequest(
                        "Title",
                        "Description",
                        null,
                        Instant.EPOCH.plusNanos(123456789),
                        Instant.EPOCH.plusSeconds(1),
                        "Atlanta");
        assertThat(request.eventStart()).isEqualTo(Instant.EPOCH.plusNanos(123456000));
        assertThat(request.toString()).doesNotContain("Title", "Description", "Atlanta");
    }

    @Test
    void rejectsOutOfRangeTimestampsWithoutRestrictingPastDates() {
        var min = Instant.parse("-4713-11-24T00:00:00Z");
        var end = Instant.parse("+294277-01-01T00:00:00Z");
        var request =
                new PublishOpportunityRequest(
                        "Title", "Description", null, min, end.minusNanos(1000), "Atlanta");
        assertThat(request.eventStart()).isEqualTo(min);
        assertThat(request.eventEnd()).isEqualTo(end.minusNanos(1000));
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "Title",
                                        "Description",
                                        null,
                                        min.minusNanos(1000),
                                        Instant.EPOCH,
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "Title",
                                        "Description",
                                        null,
                                        Instant.EPOCH,
                                        end,
                                        "Atlanta"))
                .isInstanceOf(OpportunityFailure.class);
    }

    private void manager() {
        when(profiles.me(headers))
                .thenReturn(
                        new ProfileResponse(
                                UUID.randomUUID(),
                                "a@example.test",
                                UserRole.ORGANIZATION,
                                "A",
                                "B",
                                null,
                                null,
                                org,
                                null));
        when(organizations.existsById(org)).thenReturn(true);
    }

    @Test
    void publishingBoundaryRejectsPastEqualAndSubMicrosecondStarts() {
        manager();
        for (Instant start : List.of(NOW.minusSeconds(1), NOW, NOW.plusNanos(1))) {
            var request =
                    new PublishOpportunityRequest(
                            "Title", "Description", null, start, NOW.plusSeconds(3600), "Atlanta");
            assertThatThrownBy(() -> service.publish(headers, request))
                    .isInstanceOf(OpportunityFailure.class)
                    .extracting("code")
                    .isEqualTo("EVENT_START_NOT_FUTURE");
        }
        verifyNoInteractions(events);
    }

    @Test
    void nextStoredMicrosecondIsFutureAndUsesServerClockOnce() {
        manager();
        var nextMicro = Instant.parse("2027-01-01T00:00:00.123457Z");
        var request =
                new PublishOpportunityRequest(
                        "Title", "Description", null, nextMicro, NOW.plusSeconds(3600), "Atlanta");
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.publish(headers, request).eventStart()).isEqualTo(nextMicro);
        verify(clock).instant();
    }

    @Test
    void listingCapturesOneCutoffAtStoredPrecisionForSqlPagination() {
        manager();
        var cutoff = Instant.parse("2027-01-01T00:00:00.123456Z");
        when(events.findByOrganizationIdAndEventStartAfterOrderByEventStartAscIdAsc(
                        org, cutoff, PageRequest.of(2, 3)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(2, 3), false));
        assertThat(service.list(headers, org, 2, 3).items()).isEmpty();
        verify(events)
                .findByOrganizationIdAndEventStartAfterOrderByEventStartAscIdAsc(
                        org, cutoff, PageRequest.of(2, 3));
        verify(clock).instant();
    }

    @Test
    void directRequestsCannotBypassRegistrationValidation() {
        for (String url :
                List.of(
                        "https://example.org/" + (char) 0xD800,
                        "https://example.org/" + (char) 0x0000,
                        "javascript:alert(1)")) {
            assertThatThrownBy(
                            () ->
                                    new PublishOpportunityRequest(
                                            "Title",
                                            "Description",
                                            null,
                                            NOW.plusSeconds(1),
                                            NOW.plusSeconds(3600),
                                            "Atlanta",
                                            RegistrationType.EXTERNAL,
                                            url))
                    .isInstanceOf(OpportunityFailure.class)
                    .extracting("code")
                    .isEqualTo("INVALID_REGISTRATION");
        }
        assertThatThrownBy(
                        () ->
                                new PublishOpportunityRequest(
                                        "Title",
                                        "Description",
                                        null,
                                        NOW.plusSeconds(1),
                                        NOW.plusSeconds(3600),
                                        "Atlanta",
                                        null,
                                        null))
                .isInstanceOf(OpportunityFailure.class);
    }
}
