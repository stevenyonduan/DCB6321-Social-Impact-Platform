package edu.gatech.sip6321.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.opportunity.*;
import edu.gatech.sip6321.organization.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.*;

class EventRegistrationServiceTest {
    static final Instant NOW = Instant.parse("2026-11-16T18:00:00.123456789Z");
    static final UUID USER = UUID.randomUUID(), EVENT = UUID.randomUUID(), ORG = UUID.randomUUID();
    static final List<String> HEADERS = List.of("Bearer test-access");
    OrganizationAuthorization auth = mock(OrganizationAuthorization.class);
    EventRepository events = mock(EventRepository.class);
    EventRegistrationRepository rows = mock(EventRegistrationRepository.class);
    EventRegistrationService service =
            new EventRegistrationService(auth, events, rows, Clock.fixed(NOW, ZoneOffset.UTC));
    Event event = mock(Event.class);
    EventRegistration row = mock(EventRegistration.class);
    EventRegistrationResponse response =
            new EventRegistrationResponse(
                    UUID.randomUUID(),
                    EVENT,
                    USER,
                    NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));

    ProfileResponse account(OrganizationRole role) {
        return new ProfileResponse(
                USER,
                "private@example.test",
                UserRole.STANDARD,
                "First",
                "Last",
                null,
                null,
                role == null ? null : ORG,
                role,
                NOW);
    }

    OpportunityResponse opportunity(Instant start, RegistrationType type) {
        return new OpportunityResponse(
                EVENT,
                ORG,
                USER,
                "Cleanup",
                "Description",
                null,
                start,
                start.plusSeconds(3600),
                "Atlanta",
                NOW,
                type,
                null);
    }

    @BeforeEach
    void setup() {
        when(auth.account(HEADERS)).thenReturn(account(null));
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
        when(event.response())
                .thenReturn(opportunity(NOW.plusSeconds(3600), RegistrationType.INTERNAL));
        when(row.response()).thenReturn(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "OWNER", "ADMIN"})
    void allVolunteerAccountsUseOnlyVerifiedIdentity(String role) {
        when(auth.account(HEADERS))
                .thenReturn(
                        account(role.equals("STANDARD") ? null : OrganizationRole.valueOf(role)));
        when(rows.insertIfAbsent(EVENT, USER)).thenReturn(1);
        when(rows.findByEventIdAndUserId(EVENT, USER)).thenReturn(Optional.of(row));
        assertThat(service.register(HEADERS, EVENT)).isEqualTo(response);
        verify(auth, never()).manager(any());
        verify(rows).insertIfAbsent(EVENT, USER);
    }

    @Test
    void duplicateIsStableConflictWithoutAttemptingToReadOtherRows() {
        failure(() -> service.register(HEADERS, EVENT), 409, "ALREADY_REGISTERED");
        verify(rows, never()).findByEventIdAndUserId(any(), any());
    }

    @Test
    void missingEventAndExternalRejectWithoutWriting() {
        when(events.findById(EVENT)).thenReturn(Optional.empty());
        failure(() -> service.register(HEADERS, EVENT), 404, "EVENT_NOT_FOUND");
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
        when(event.response())
                .thenReturn(opportunity(NOW.plusSeconds(10), RegistrationType.EXTERNAL));
        failure(() -> service.register(HEADERS, EVENT), 400, "INTERNAL_REGISTRATION_REQUIRED");
        verifyNoInteractions(rows);
    }

    @Test
    void fullPrecisionClockRejectsPastEqualAndStoredMicrosecondBeforeNow() {
        for (Instant start :
                List.of(
                        NOW.minusSeconds(1),
                        NOW,
                        NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
            when(event.response()).thenReturn(opportunity(start, RegistrationType.INTERNAL));
            failure(() -> service.register(HEADERS, EVENT), 400, "EVENT_ALREADY_STARTED");
            failure(() -> service.cancel(HEADERS, EVENT), 400, "EVENT_ALREADY_STARTED");
        }
        verifyNoInteractions(rows);
        when(event.response())
                .thenReturn(
                        opportunity(
                                Instant.parse("2026-11-16T18:00:00.123457Z"),
                                RegistrationType.INTERNAL));
        when(rows.insertIfAbsent(EVENT, USER)).thenReturn(1);
        when(rows.findByEventIdAndUserId(EVENT, USER)).thenReturn(Optional.of(row));
        assertThat(service.register(HEADERS, EVENT)).isEqualTo(response);
    }

    @Test
    void statusIncludesOnlyOwnRowAndWorksForHistoricalAndExternalEvents() {
        when(rows.findByEventIdAndUserId(EVENT, USER))
                .thenReturn(Optional.of(row), Optional.empty());
        when(event.response())
                .thenReturn(opportunity(NOW.minusSeconds(1), RegistrationType.EXTERNAL));
        var registered = service.status(HEADERS, EVENT);
        assertThat(registered.registered()).isTrue();
        assertThat(registered.registration().id()).isEqualTo(response.id());
        assertThat(service.status(HEADERS, EVENT))
                .isEqualTo(new RegistrationStatusResponse(EVENT, false, null));
        verify(rows, times(2)).findByEventIdAndUserId(EVENT, USER);
    }

    @Test
    void cancellationIsOneAtomicUserScopedDeleteAndMissingIs404() {
        when(rows.cancel(EVENT, USER)).thenReturn(1, 0);
        service.cancel(HEADERS, EVENT);
        failure(() -> service.cancel(HEADERS, EVENT), 404, "REGISTRATION_NOT_FOUND");
        verify(rows, times(2)).cancel(EVENT, USER);
        verify(events, never()).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN"})
    void managersOnlyReadAttendeesWithinTheirOrganization(String role) {
        when(auth.manager(HEADERS)).thenReturn(account(OrganizationRole.valueOf(role)));
        when(rows.attendees(EVENT, PageRequest.of(0, 20))).thenReturn(new SliceImpl<>(List.of()));
        assertThat(service.attendees(HEADERS, EVENT, 0, 20).items()).isEmpty();
        var other = opportunity(NOW, RegistrationType.INTERNAL);
        when(event.response())
                .thenReturn(
                        new OpportunityResponse(
                                EVENT,
                                UUID.randomUUID(),
                                USER,
                                other.title(),
                                other.description(),
                                null,
                                NOW,
                                NOW.plusSeconds(60),
                                "Atlanta",
                                NOW,
                                RegistrationType.INTERNAL,
                                null));
        assertThatThrownBy(() -> service.attendees(HEADERS, EVENT, 0, 20))
                .isInstanceOf(OrganizationFailure.class);
        verify(rows, times(1)).attendees(any(), any());
    }

    @Test
    void personalPaginationIsBoundedAndAccountScoped() {
        when(rows.mine(USER, PageRequest.of(1, 2)))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(1, 2), false));
        assertThat(service.mine(HEADERS, 1, 2))
                .isEqualTo(new RegistrationPage<>(List.of(), 1, 2, false));
        for (int[] pair : new int[][] {{-1, 20}, {10001, 20}, {0, 0}, {0, 101}})
            failure(() -> service.mine(HEADERS, pair[0], pair[1]), 400, "INVALID_REQUEST");
        verify(rows, times(1)).mine(any(), any());
    }

    static void failure(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        RegistrationFailure.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo(code);
                        });
    }
}
