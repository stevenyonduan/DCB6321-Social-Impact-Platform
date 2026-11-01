package edu.gatech.sip6321.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import java.time.Instant;
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
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(
        controllers = ProfileController.class,
        properties = {
            "SIP_AUTH_ENABLED=true",
            "SIP_CORS_ALLOWED_ORIGINS=https://frontend.example.test"
        })
@Import({ProfileService.class, ProfileExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class ProfileControllerTest {
    private static final UUID ID = UUID.fromString("a21bb7b3-cbcb-4829-860b-b7aee3878aab");
    private static final String TOKEN = "access-never-log";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService users;
    private User user;

    @BeforeEach
    void setup() {
        user = mock(User.class);
        when(user.getId()).thenReturn(ID);
        when(user.getEmail()).thenReturn("student@example.test");
        when(user.getRole()).thenReturn(UserRole.STANDARD);
        when(user.getFirstName()).thenReturn("Test");
        when(user.getLastName()).thenReturn("Student");
        when(user.getCreatedAt()).thenReturn(Instant.parse("2026-11-01T18:00:00Z"));
        when(auth.verifyAccessToken(TOKEN)).thenReturn(ID);
        when(users.getById(ID)).thenReturn(user);
    }

    @Test
    void returnsOnlyAllowlistedFieldsAndIgnoresClientIdentity(CapturedOutput output)
            throws Exception {
        var result =
                mvc.perform(
                                get("/api/users/me")
                                        .header("Authorization", "Bearer " + TOKEN)
                                        .param("id", UUID.randomUUID().toString())
                                        .param("email", "other@example.test")
                                        .param("role", "ADMIN"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.id").value(ID.toString()))
                        .andExpect(jsonPath("$.email").value("student@example.test"))
                        .andExpect(jsonPath("$.role").value("STANDARD"))
                        .andExpect(jsonPath("$.firstName").value("Test"))
                        .andExpect(jsonPath("$.lastName").value("Student"))
                        .andExpect(jsonPath("$.createdAt").value("2026-11-01T18:00:00Z"))
                        .andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder(
                        "id",
                        "email",
                        "role",
                        "firstName",
                        "lastName",
                        "bio",
                        "avatarUrl",
                        "organizationId",
                        "createdAt");
        for (String name : new String[] {"bio", "avatarUrl", "organizationId"})
            assertThat(body.path(name).isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(TOKEN, "refresh_token", "password", "session", "newRecord");
        assertThat(output.getAll()).doesNotContain(TOKEN);
        verify(auth).verifyAccessToken(TOKEN);
        verify(users).getById(ID);
        verifyNoMoreInteractions(auth, users);
    }

    @Test
    void mapsPopulatedOptionalFields() throws Exception {
        var organization = UUID.randomUUID();
        when(user.getBio()).thenReturn("Volunteer");
        when(user.getAvatarUrl()).thenReturn("https://example.test/avatar.png");
        when(user.getOrganizationId()).thenReturn(organization);
        mvc.perform(get("/api/users/me").header("Authorization", "bearer   " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Volunteer"))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.test/avatar.png"))
                .andExpect(jsonPath("$.organizationId").value(organization.toString()));
    }

    @Test
    void rejectsMissingHeader() throws Exception {
        unauthorized(null);
        verifyNoInteractions(auth, users);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Basic abc",
                "Bearer",
                "Bearer ",
                "Bearer one two",
                "Bearer abc,Bearer def",
                "Bearer abc\t",
                "Bearer abc\n",
                "Bearer \u00e9",
                "Bearer abc=def"
            })
    void rejectsMalformedHeaders(String header) throws Exception {
        unauthorized(header);
        verifyNoInteractions(auth, users);
    }

    @Test
    void rejectsDuplicateAndOversizedHeaders() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer one", "Bearer two"))
                .andExpect(status().isUnauthorized());
        unauthorized("Bearer " + "x".repeat(16385));
        verifyNoInteractions(auth, users);
    }

    @Test
    void invalidOrExpiredTokenNeverReachesDatabase() throws Exception {
        when(auth.verifyAccessToken(TOKEN)).thenThrow(ProfileFailure.unauthorized());
        unauthorized("Bearer " + TOKEN);
        verifyNoInteractions(users);
    }

    @Test
    void sanitizesMissingAccountAndDatabaseFailures(CapturedOutput output) throws Exception {
        for (RuntimeException failure :
                new RuntimeException[] {
                    new AccountNotFoundException(),
                    new DataAccessResourceFailureException("SQL secret " + TOKEN),
                    new CannotCreateTransactionException("db password secret"),
                    new TransactionSystemException("SQL secret")
                }) {
            doThrow(failure).when(users).getById(ID);
            var result =
                    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + TOKEN))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    jsonPath("$.code")
                                            .value(
                                                    failure instanceof AccountNotFoundException
                                                            ? "ACCOUNT_PROVISIONING_REQUIRED"
                                                            : "ACCOUNT_UNAVAILABLE"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString() + output.getAll())
                    .doesNotContain(TOKEN, "SQL secret", "db password secret");
        }
    }

    @Test
    void sanitizesAuthAndUnexpectedFailures(CapturedOutput output) throws Exception {
        for (RuntimeException failure :
                new RuntimeException[] {
                    ProfileFailure.fromAuth(SignupFailure.unavailable()),
                    new IllegalStateException("secret " + TOKEN)
                }) {
            doThrow(failure).when(auth).verifyAccessToken(TOKEN);
            var result =
                    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + TOKEN))
                            .andExpect(status().is(failure instanceof ProfileFailure ? 503 : 500))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString() + output.getAll())
                    .doesNotContain(TOKEN, "secret");
        }
        verifyNoInteractions(users);
    }

    @Test
    void preservesCorsPreflightWithoutAuthentication() throws Exception {
        mvc.perform(
                        options("/api/users/me")
                                .header("Origin", "https://frontend.example.test")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://frontend.example.test"));
        verifyNoInteractions(auth, users);
    }

    private void unauthorized(String header) throws Exception {
        var request = get("/api/users/me");
        if (header != null) request.header("Authorization", header);
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
