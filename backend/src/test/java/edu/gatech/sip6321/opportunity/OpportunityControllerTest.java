package edu.gatech.sip6321.opportunity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.organization.OrganizationRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(
        controllers = OpportunityController.class,
        properties = {
            "SIP_AUTH_ENABLED=true",
            "SIP_CORS_ALLOWED_ORIGINS=https://frontend.example.test"
        })
@Import({OpportunityService.class, ProfileService.class, OpportunityExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class OpportunityControllerTest {
    static final UUID ORG = UUID.randomUUID();
    static final UUID USER = UUID.randomUUID();
    static final String POST = "/api/organizations/me/opportunities";
    static final String GET = "/api/organizations/" + ORG + "/opportunities";
    static final String BODY =
            """
        {"title":"Cleanup","description":"Help the park","location":"Atlanta",
         "eventStart":"2027-01-16T09:00:00-05:00","eventEnd":"2027-01-16T12:00:00-05:00","imageUrl":null}
        """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService users;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean EventRepository events;
    User account;

    @BeforeEach
    void setup() {
        account = mock(User.class);
        when(account.getId()).thenReturn(USER);
        when(account.getRole()).thenReturn(UserRole.ORGANIZATION);
        when(account.getOrganizationId()).thenReturn(ORG);
        when(auth.verifyAccessToken("test-access")).thenReturn(USER);
        when(users.getById(USER)).thenReturn(account);
        when(organizations.existsById(ORG)).thenReturn(true);
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.findByOrganizationIdOrderByEventStartAscIdAsc(eq(ORG), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
    }

    @Test
    void publishesUsingVerifiedIdentityAndPersistedAssociation() throws Exception {
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.creatorId").value(USER.toString()))
                .andExpect(jsonPath("$.eventStart").value("2027-01-16T14:00:00Z"));
        verify(events).saveAndFlush(any(Event.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "ADMIN", "UNASSOCIATED"})
    void deniesUnauthorizedPublishing(String role) throws Exception {
        if (role.equals("UNASSOCIATED")) when(account.getOrganizationId()).thenReturn(null);
        else when(account.getRole()).thenReturn(UserRole.valueOf(role));
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(organizations, events);
    }

    @Test
    void missingAndRejectedTokensOnBothEndpoints() throws Exception {
        for (String token : List.of("", "invalid", "expired")) {
            if (!token.isEmpty())
                when(auth.verifyAccessToken(token)).thenThrow(ProfileFailure.unauthorized());
            for (var request :
                    List.of(get(GET), post(POST).contentType("application/json").content(BODY))) {
                if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"));
            }
        }
        verifyNoInteractions(users, organizations, events);
    }

    @Test
    void rejectsMalformedAndDuplicateAuthorization() throws Exception {
        for (String token : List.of("Basic abc", "Bearer one two", "Bearer a,Bearer b"))
            mvc.perform(get(GET).header("Authorization", token))
                    .andExpect(status().isUnauthorized());
        mvc.perform(get(GET).header("Authorization", "Bearer a", "Bearer b"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, users, organizations, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"organizationId", "creatorId", "id", "createdAt", "role", "unknown"})
    void rejectsProtectedOrUnknownFields(String field) throws Exception {
        var node = json.readTree(BODY).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node).put(field, UUID.randomUUID().toString());
        reject(json.writeValueAsString(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "location", "eventStart", "eventEnd"})
    void rejectsMissingNullBlankAndNonStringRequiredFields(String field) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.remove(field);
        reject(json.writeValueAsString(node));
        node.putNull(field);
        reject(json.writeValueAsString(node));
        node.put(field, " ");
        reject(json.writeValueAsString(node));
        node.put(field, 123);
        reject(json.writeValueAsString(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "location", "imageUrl"})
    void enforcesVarcharBounds(String field) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put(field, "x".repeat(256));
        reject(json.writeValueAsString(node));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2027-01-16T13:00:00Z",
                "2027-01-16T14:00:00Z",
                "2027-01-16T15:00:00",
                "not-a-time",
                "+999999999-01-01T00:00:00Z"
            })
    void rejectsInvalidOrNonIncreasingTimes(String end) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put("eventEnd", end);
        reject(json.writeValueAsString(node));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "relative/path",
                "javascript:alert(1)",
                "https://user:password@example.test/x",
                "https://example.test:99999"
            })
    void validatesImageReferences(String url) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put("imageUrl", url);
        reject(json.writeValueAsString(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "true", "{", "{\"title\":\"bad\\u0000text\"}"})
    void rejectsMalformedBodies(String body) throws Exception {
        reject(body);
    }

    @Test
    void allowsPastEventsLongDescriptionsUnicodeAndReferenceUrls() throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put("title", "😀".repeat(255));
        node.put("description", "x".repeat(40000));
        node.put("eventStart", "2000-01-01T00:00:00Z");
        node.put("eventEnd", "2000-01-01T01:00:00Z");
        node.put("imageUrl", "https://example.test/image.png");
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(json.writeValueAsString(node)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventStart").value("2000-01-01T00:00:00Z"));
    }

    @Test
    void standardAndOrganizationAccountsCanListBoundedResults() throws Exception {
        for (UserRole role : UserRole.values()) {
            when(account.getRole()).thenReturn(role);
            mvc.perform(get(GET).header("Authorization", "Bearer test-access"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
        verify(events, times(3))
                .findByOrganizationIdOrderByEventStartAscIdAsc(ORG, PageRequest.of(0, 20));
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
    void rejectsInvalidPagination(String query) throws Exception {
        mvc.perform(get(GET + "?" + query).header("Authorization", "Bearer test-access"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(events);
    }

    @Test
    void handlesMissingOrganizationsMalformedUuidAndUnsupportedRequests() throws Exception {
        when(organizations.existsById(ORG)).thenReturn(false);
        mvc.perform(get(GET).header("Authorization", "Bearer test-access"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/organizations/invalid/opportunities"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(POST).contentType("text/plain").content(BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(delete(GET))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(events);
    }

    @Test
    void sanitizesDatabaseAndUnexpectedFailures(CapturedOutput output) throws Exception {
        for (RuntimeException error :
                List.of(
                        new DataAccessResourceFailureException("secret SQL test-access"),
                        new IllegalStateException("secret SQL test-access"))) {
            doThrow(error).when(events).saveAndFlush(any());
            var result =
                    mvc.perform(
                                    post(POST)
                                            .header("Authorization", "Bearer test-access")
                                            .contentType("application/json")
                                            .content(BODY))
                            .andExpect(
                                    status().is(
                                                    error
                                                                    instanceof
                                                                    DataAccessResourceFailureException
                                                            ? 503
                                                            : 500))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString() + output.getAll())
                    .doesNotContain("secret SQL", "test-access");
        }
    }

    @Test
    void preservesCorsAndRejectsUnsupportedMethodsBeforeMvc() throws Exception {
        mvc.perform(
                        options(POST)
                                .header("Origin", "https://frontend.example.test")
                                .header("Access-Control-Request-Method", "POST")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://frontend.example.test"));
        mvc.perform(put(POST).contentType("application/json").content(BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST, OPTIONS"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post(GET).contentType("application/json").content(BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post(POST).content(BODY)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(auth, users, organizations, events);
    }

    private void reject(String body) throws Exception {
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(events);
    }
}
