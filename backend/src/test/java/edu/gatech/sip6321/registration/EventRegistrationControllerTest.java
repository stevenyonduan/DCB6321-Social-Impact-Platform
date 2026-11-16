package edu.gatech.sip6321.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.opportunity.*;
import edu.gatech.sip6321.organization.OrganizationAuthorization;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(
        controllers = EventRegistrationController.class,
        properties = {
            "SIP_AUTH_ENABLED=true",
            "SIP_CORS_ALLOWED_ORIGINS=https://frontend.example.test"
        })
@Import({
    EventRegistrationService.class,
    OrganizationAuthorization.class,
    ProfileService.class,
    RegistrationExceptionHandler.class
})
class EventRegistrationControllerTest {
    static final UUID USER = EventRegistrationServiceTest.USER,
            EVENT = EventRegistrationServiceTest.EVENT,
            ORG = EventRegistrationServiceTest.ORG;
    static final Instant NOW = EventRegistrationServiceTest.NOW;
    static final String ME = "/api/opportunities/" + EVENT + "/registrations/me";
    static final String MINE = "/api/users/me/registrations";
    static final String ATTENDEES =
            "/api/organizations/me/opportunities/" + EVENT + "/registrations";
    @Autowired MockMvc mvc;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService users;
    @MockitoBean EventRepository events;
    @MockitoBean EventRegistrationRepository rows;
    @MockitoBean Clock clock;
    User user;
    Event event;

    @BeforeEach
    void setup() {
        user = mock(User.class);
        event = mock(Event.class);
        when(auth.verifyAccessToken("test-access")).thenReturn(USER);
        when(users.getById(USER)).thenReturn(user);
        when(user.getId()).thenReturn(USER);
        when(user.getRole()).thenReturn(UserRole.STANDARD);
        when(user.getOrganizationId()).thenReturn(ORG);
        when(user.getOrganizationRole()).thenReturn(OrganizationRole.OWNER);
        when(clock.instant()).thenReturn(NOW);
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
        when(event.response())
                .thenReturn(
                        new OpportunityResponse(
                                EVENT,
                                ORG,
                                USER,
                                "Cleanup",
                                "Help",
                                null,
                                NOW.plusSeconds(60),
                                NOW.plusSeconds(3600),
                                "Atlanta",
                                NOW,
                                RegistrationType.INTERNAL,
                                null));
        var row = mock(EventRegistration.class);
        when(row.response())
                .thenReturn(new EventRegistrationResponse(UUID.randomUUID(), EVENT, USER, NOW));
        when(rows.insertIfAbsent(EVENT, USER)).thenReturn(1);
        when(rows.findByEventIdAndUserId(EVENT, USER)).thenReturn(Optional.of(row));
        when(rows.cancel(EVENT, USER)).thenReturn(1);
        when(rows.mine(eq(USER), any())).thenReturn(new SliceImpl<>(List.of()));
        when(rows.attendees(eq(EVENT), any())).thenReturn(new SliceImpl<>(List.of()));
    }

    MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-access");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "OWNER", "ADMIN"})
    void volunteersRegisterRegardlessOfOrganizationRole(String role) throws Exception {
        when(user.getOrganizationRole())
                .thenReturn(role.equals("STANDARD") ? null : OrganizationRole.valueOf(role));
        mvc.perform(authorized(post(ME)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.registeredAt").value(NOW.toString()));
        verify(rows).insertIfAbsent(EVENT, USER);
    }

    @Test
    void allRoutesRequireValidSingleBearerAndPersistedAccount() throws Exception {
        for (String token : List.of("", "invalid", "expired")) {
            if (!token.isEmpty())
                when(auth.verifyAccessToken(token)).thenThrow(ProfileFailure.unauthorized());
            for (var request : List.of(post(ME), get(ME), delete(ME), get(MINE), get(ATTENDEES))) {
                if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            }
        }
        mvc.perform(get(ME).header("Authorization", "Bearer a", "Bearer b"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(events, rows);
        when(users.getById(USER)).thenThrow(new AccountNotFoundException());
        mvc.perform(authorized(post(ME)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_REQUIRED"));
    }

    @Test
    void statusCancellationAndListsHaveConsistentContracts() throws Exception {
        mvc.perform(authorized(get(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.registration.id").exists())
                .andExpect(jsonPath("$.registration.userId").doesNotExist());
        when(rows.findByEventIdAndUserId(EVENT, USER)).thenReturn(Optional.empty());
        mvc.perform(authorized(get(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "\"registration\":null")));
        mvc.perform(authorized(delete(ME)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        for (String path : List.of(MINE, ATTENDEES))
            mvc.perform(authorized(get(path)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void validationAndExpectedFailuresAreSanitized() throws Exception {
        mvc.perform(authorized(get("/api/opportunities/invalid/registrations/me")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        when(events.findById(EVENT)).thenReturn(Optional.empty());
        for (var request : List.of(post(ME), get(ME), delete(ME), get(ATTENDEES)))
            mvc.perform(authorized(request))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
        when(rows.insertIfAbsent(EVENT, USER)).thenReturn(0);
        mvc.perform(authorized(post(ME)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));
        when(rows.cancel(EVENT, USER)).thenReturn(0);
        mvc.perform(authorized(delete(ME)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        for (var request : List.of(post(ME), delete(ME)))
            mvc.perform(authorized(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EVENT_ALREADY_STARTED"));
    }

    @Test
    void externalAndIdentityBodiesNeverWrite() throws Exception {
        var response = event.response();
        when(event.response())
                .thenReturn(
                        new OpportunityResponse(
                                EVENT,
                                ORG,
                                USER,
                                response.title(),
                                response.description(),
                                null,
                                response.eventStart(),
                                response.eventEnd(),
                                response.location(),
                                NOW,
                                RegistrationType.EXTERNAL,
                                "https://example.test"));
        mvc.perform(authorized(post(ME)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTERNAL_REGISTRATION_REQUIRED"));
        for (var request : List.of(post(ME), delete(ME)))
            mvc.perform(
                            authorized(request)
                                    .contentType("application/json")
                                    .content("{\"userId\":\"someone-else\",\"role\":\"OWNER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN"})
    void managersMayReadOnlyOwnOrganizationAndNeverEmails(String role) throws Exception {
        when(user.getOrganizationRole()).thenReturn(OrganizationRole.valueOf(role));
        when(rows.attendees(eq(EVENT), any()))
                .thenReturn(
                        new SliceImpl<>(
                                List.of(
                                        new AttendeeResponse(
                                                UUID.randomUUID(), USER, "First", "Last", NOW))));
        mvc.perform(authorized(get(ATTENDEES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].firstName").value("First"))
                .andExpect(jsonPath("$.items[0].email").doesNotExist());
        when(user.getOrganizationId()).thenReturn(UUID.randomUUID());
        mvc.perform(authorized(get(ATTENDEES))).andExpect(status().isForbidden());
        when(user.getOrganizationRole()).thenReturn(null);
        mvc.perform(authorized(get(ATTENDEES))).andExpect(status().isForbidden());
        verify(rows, times(1)).attendees(any(), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "page=-1",
                "page=10001",
                "size=0",
                "size=101",
                "page=abc",
                "size=2147483648"
            })
    void bothListsRejectBadPagination(String query) throws Exception {
        for (String path : List.of(MINE, ATTENDEES))
            mvc.perform(authorized(get(path + "?" + query)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(rows);
    }

    @Test
    void failuresNeverExposeDriverOrInternalMessages() throws Exception {
        for (RuntimeException failure :
                List.of(
                        new DataAccessResourceFailureException("private SQL test-access"),
                        new DataIntegrityViolationException("private SQL test-access"),
                        new IllegalStateException("private SQL test-access"))) {
            doThrow(failure).when(rows).insertIfAbsent(EVENT, USER);
            var result =
                    mvc.perform(authorized(post(ME)))
                            .andExpect(
                                    status().is(failure instanceof DataAccessException ? 503 : 500))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("private", "SQL", "test-access");
        }
    }

    @Test
    void unsupportedMethodsAndCorsKeepExistingConventions() throws Exception {
        for (String path : List.of(ME, MINE, ATTENDEES))
            mvc.perform(put(path))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(
                        options(ME)
                                .header("Origin", "https://frontend.example.test")
                                .header("Access-Control-Request-Method", "DELETE")
                                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://frontend.example.test"));
    }
}
