package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(
        controllers = OrganizationController.class,
        properties = {
            "SIP_AUTH_ENABLED=true",
            "SIP_CORS_ALLOWED_ORIGINS=https://frontend.example.test"
        })
@Import({OrganizationService.class, ProfileService.class, OrganizationExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class OrganizationControllerTest {
    private static final UUID ID = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String PATH = "/api/organizations/" + ID;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService users;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean OrganizationProvisioningService provisioning;
    private User account;
    private Organization organization;

    @BeforeEach
    void setup() {
        account = mock(User.class);
        when(account.getId()).thenReturn(USER);
        when(account.getRole()).thenReturn(UserRole.ORGANIZATION);
        when(account.getOrganizationId()).thenReturn(ID);
        when(auth.verifyAccessToken("test-access")).thenReturn(USER);
        when(users.getById(USER)).thenReturn(account);
        organization = Organization.create("Original", "Description", "https://example.test");
        // Generated columns are populated by PostgreSQL in the integration suite.
        when(organizations.findById(ID)).thenReturn(Optional.of(organization));
    }

    @Test
    void createsAnUnverifiedOrganizationForTheAuthenticatedStandardAccount() throws Exception {
        when(users.lockById(USER)).thenReturn(account);
        when(account.getRole()).thenReturn(UserRole.STANDARD);
        when(account.getOrganizationId()).thenReturn(null);
        when(provisioning.create("Garden", null, null))
                .thenReturn(
                        new OrganizationResponse(ID, "Garden", null, null, false, Instant.EPOCH));

        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"name\":\"Garden\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PATH))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.isVerified").value(false));
        verify(account).assignCreatedOrganization(ID);
        verify(auth).verifyAccessToken("test-access");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"name\":null}",
                "{\"name\":\"Garden\",\"role\":\"ORGANIZATION\"}",
                "{\"name\":\"Garden\",\"organizationId\":null}",
                "{\"name\":\"Garden\",\"isVerified\":true}"
            })
    void rejectsInvalidCreationFields(String body) throws Exception {
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(provisioning, users);
    }

    @Test
    void creationRejectsMissingAuthAndIneligibleAccounts() throws Exception {
        String body = "{\"name\":\"Garden\"}";
        mvc.perform(post("/api/organizations").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        when(users.lockById(USER)).thenThrow(new AccountNotFoundException());
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_REQUIRED"));
        doReturn(account).when(users).lockById(USER);
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ALREADY_ASSOCIATED"));
        when(account.getRole()).thenReturn(UserRole.ADMIN);
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(provisioning);
    }

    @Test
    void readsAllowlistedProfileByIdAndOwnAssociation() throws Exception {
        for (String path : new String[] {PATH, "/api/organizations/me"}) {
            var result =
                    mvc.perform(
                                    get(path)
                                            .header("Authorization", "Bearer test-access")
                                            .param("organizationId", UUID.randomUUID().toString()))
                            .andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.name").value("Original"))
                            .andExpect(jsonPath("$.isVerified").value(false))
                            .andReturn();
            assertThat(json.readTree(result.getResponse().getContentAsString()).propertyNames())
                    .containsExactlyInAnyOrder(
                            "id", "name", "description", "website", "isVerified", "createdAt");
        }
        verify(organizations, times(2)).findById(ID);
    }

    @Test
    void standardUsersCanReadOtherOrganizations() throws Exception {
        when(account.getRole()).thenReturn(UserRole.STANDARD);
        when(account.getOrganizationId()).thenReturn(null);
        mvc.perform(get(PATH).header("Authorization", "Bearer test-access"))
                .andExpect(status().isOk());
    }

    @Test
    void patchPreservesOmissionsAndClearsExplicitNullableFields() throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"name\":\"Updated\",\"website\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.website").doesNotExist());
        assertThat(organization.getWebsite()).isNull();
        verify(organizations).flush();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "ADMIN", "OTHER_ORGANIZATION", "UNASSOCIATED"})
    void deniesUnauthorizedEditsAndOwnProfileWithoutLookingUpTarget(String kind) throws Exception {
        if (kind.equals("OTHER_ORGANIZATION"))
            when(account.getOrganizationId()).thenReturn(UUID.randomUUID());
        else if (kind.equals("UNASSOCIATED")) when(account.getOrganizationId()).thenReturn(null);
        else when(account.getRole()).thenReturn(UserRole.valueOf(kind));
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"name\":\"Denied\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        if (!kind.equals("OTHER_ORGANIZATION"))
            mvc.perform(get("/api/organizations/me").header("Authorization", "Bearer test-access"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(organizations);
        assertThat(organization.getName()).isEqualTo("Original");
    }

    @Test
    void deniesMissingAndRejectedTokensOnEveryEndpoint() throws Exception {
        for (boolean rejected : new boolean[] {false, true}) {
            if (rejected)
                when(auth.verifyAccessToken("test-access"))
                        .thenThrow(ProfileFailure.unauthorized());
            var get = get(PATH);
            var me = get("/api/organizations/me");
            var edit = patch(PATH).contentType("application/json").content("{}");
            for (var request :
                    new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                            [] {get, me, edit}) {
                if (rejected) request.header("Authorization", "Bearer test-access");
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"));
            }
        }
        verifyNoInteractions(users, organizations);
    }

    @Test
    void rejectsMalformedAndDuplicateHeaders() throws Exception {
        for (String header : new String[] {"Basic abc", "Bearer one two", "Bearer abc,Bearer def"})
            mvc.perform(get(PATH).header("Authorization", header))
                    .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer abc", "Bearer def"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, users, organizations);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"id\":null}",
                "{\"isVerified\":true}",
                "{\"createdAt\":null}",
                "{\"organizationId\":null}",
                "{\"role\":\"ADMIN\"}",
                "{\"unexpected\":1}",
                "{\"name\":null}",
                "{\"name\":\" \"}",
                "{\"name\":123}",
                "{\"description\":[]}",
                "{\"website\":\"javascript:alert(1)\"}",
                "{\"website\":\"https://user:pass@example.test\"}",
                "{\"website\":\"relative/path\"}",
                "{\"website\":\"\"}",
                "{\"website\":\"https://example.test:99999\"}",
                "[]",
                "null",
                "true",
                "{",
                "{\"name\":\"bad\\u0000name\"}"
            })
    void rejectsInvalidPatchBeforeMutation(String body) throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(organizations);
    }

    @Test
    void validatesLengthsAndUnicodeAndAllowsEmptyPatch() throws Exception {
        for (String field : new String[] {"name", "website"})
            mvc.perform(
                            patch(PATH)
                                    .header("Authorization", "Bearer test-access")
                                    .contentType("application/json")
                                    .content("{\"" + field + "\":\"" + "x".repeat(256) + "\"}"))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"name\":\"" + "😀".repeat(255) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingOrganizationIs404OnlyAfterAuthorization() throws Exception {
        when(organizations.findById(ID)).thenReturn(Optional.empty());
        mvc.perform(get(PATH).header("Authorization", "Bearer test-access"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/organizations/me").header("Authorization", "Bearer test-access"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sanitizesDatabaseAndUnexpectedFailures(CapturedOutput output) throws Exception {
        for (RuntimeException error :
                new RuntimeException[] {
                    new DataAccessResourceFailureException("secret SQL test-access"),
                    new IllegalStateException("secret SQL test-access")
                }) {
            doThrow(error).when(organizations).findById(ID);
            var result =
                    mvc.perform(get(PATH).header("Authorization", "Bearer test-access"))
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
    void rejectsBadUuidAndMediaTypeAndPreservesCors() throws Exception {
        mvc.perform(get("/api/organizations/invalid").header("Authorization", "Bearer test-access"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(PATH).contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(
                        options(PATH)
                                .header("Origin", "https://frontend.example.test")
                                .header("Access-Control-Request-Method", "PATCH")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://frontend.example.test"));
    }
}
