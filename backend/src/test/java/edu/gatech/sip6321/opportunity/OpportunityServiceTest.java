package edu.gatech.sip6321.opportunity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityServiceTest {
    final ProfileService profiles = mock(ProfileService.class);
    final OrganizationRepository organizations = mock(OrganizationRepository.class);
    final EventRepository events = mock(EventRepository.class);
    final OpportunityService service = new OpportunityService(profiles, organizations, events);
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
}
