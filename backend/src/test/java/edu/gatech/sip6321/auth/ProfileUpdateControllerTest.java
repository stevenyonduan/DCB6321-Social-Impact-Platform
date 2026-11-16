package edu.gatech.sip6321.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
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
@Import({
    ProfileService.class,
    UserService.class,
    ProfileExceptionHandler.class,
    ProfileRequestFilter.class
})
@ActiveProfiles("database")
class ProfileUpdateControllerTest {
    static final UUID ID = UUID.randomUUID();
    static final String PATH = "/api/users/me";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserRepository users;
    private User user;

    @BeforeEach
    void setup() {
        user = mock(User.class);
        when(user.getId()).thenReturn(ID);
        when(user.getEmail()).thenReturn("student@example.test");
        when(user.getRole()).thenReturn(UserRole.STANDARD);
        when(user.getFirstName()).thenReturn("Jordan");
        when(user.getLastName()).thenReturn("Taylor");
        when(user.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(auth.verifyAccessToken("test-access")).thenReturn(ID);
        when(users.findUserById(ID)).thenReturn(Optional.of(user));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"firstName\":\"Jordan\"}",
                "{\"lastName\":\"Taylor\"}",
                "{\"bio\":\"Gardens\"}",
                "{\"bio\":null}",
                "{\"firstName\":\"Jordan\",\"lastName\":\"Taylor\",\"bio\":\"Gardens\"}"
            })
    void acceptsApprovedUpdatesAndReturnsFullContract(String body) throws Exception {
        var response =
                mvc.perform(
                                patch(PATH)
                                        .header("Authorization", "Bearer test-access")
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.id").value(ID.toString()))
                        .andExpect(jsonPath("$.firstName").value("Jordan"))
                        .andExpect(jsonPath("$.lastName").value("Taylor"))
                        .andExpect(jsonPath("$.role").value("STANDARD"))
                        .andReturn()
                        .getResponse();
        assertThat(json.readTree(response.getContentAsString()).propertyNames())
                .containsExactlyInAnyOrder(
                        "id",
                        "email",
                        "role",
                        "firstName",
                        "lastName",
                        "bio",
                        "avatarUrl",
                        "organizationId",
                        "organizationRole",
                        "createdAt");
        verify(auth).verifyAccessToken("test-access");
        verify(users).findUserById(ID);
        verify(users).flush();
        verifyNoMoreInteractions(auth, users);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "null",
                "[]",
                "{}",
                "{",
                "{\"bio\":",
                "{\"firstName\":null}",
                "{\"lastName\":null}",
                "{\"firstName\":\"\"}",
                "{\"lastName\":\"  \"}",
                "{\"firstName\":\"\\u2003\"}",
                "{\"firstName\":\"\\u00a0\"}",
                "{\"firstName\":12}",
                "{\"lastName\":true}",
                "{\"bio\":{}}",
                "{\"bio\":[]}",
                "{\"firstName\":\"Valid\",\"lastName\":null}",
                "{\"bio\":\"\\u0000\"}",
                "{\"bio\":\"\\ud800\"}",
                "{\"unknown\":\"x\"}"
            })
    void rejectsInvalidBodiesWithoutWriting(String body) throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "email",
                "role",
                "organizationId",
                "organizationRole",
                "createdAt",
                "avatarUrl",
                "password",
                "accessToken"
            })
    void rejectsProtectedPropertiesEvenAlongsideValidUpdates(String field) throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"firstName\":\"Jordan\",\"" + field + "\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"firstName", "lastName"})
    void unicodeLimitsCountCodePoints(String field) throws Exception {
        String value = "🌱".repeat(100);
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"" + field + "\":\"" + value + "\"}"))
                .andExpect(status().isOk());
        clearInvocations(users);
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"" + field + "\":\"" + value + "x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic invalid", "Bearer expired", "Bearer one two"})
    void rejectsMissingMalformedAndExpiredCredentials(String header) throws Exception {
        when(auth.verifyAccessToken("expired")).thenThrow(ProfileFailure.unauthorized());
        var request = patch(PATH).contentType("application/json").content("{\"bio\":null}");
        if (!header.isEmpty()) request.header("Authorization", header);
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(users);
    }

    @Test
    void clientQueryIdentityCannotSelectAnotherAccount() throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .param("id", UUID.randomUUID().toString())
                                .param("role", "ADMIN")
                                .contentType("application/json")
                                .content("{\"bio\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
        verify(users).findUserById(ID);
        verify(users).flush();
        verifyNoMoreInteractions(users);
    }

    @Test
    void databaseAndCommitFailuresAreSanitized() throws Exception {
        for (RuntimeException failure :
                new RuntimeException[] {
                    new DataAccessResourceFailureException("SQL secret"),
                    new CannotCreateTransactionException("password secret"),
                    new TransactionSystemException("commit secret")
                }) {
            doThrow(failure).when(users).flush();
            var response =
                    mvc.perform(
                                    patch(PATH)
                                            .header("Authorization", "Bearer test-access")
                                            .contentType("application/json")
                                            .content("{\"bio\":null}"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString()).doesNotContain("SQL", "secret", "password");
        }
    }

    @Test
    void missingAccountDoesNotProvision() throws Exception {
        when(users.findUserById(ID)).thenReturn(Optional.empty());
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"bio\":null}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_REQUIRED"));
        verify(users, never()).flush();
    }

    @Test
    void preservesUpstreamAuthFailuresAndSanitizesUnexpectedErrors() throws Exception {
        for (var failure :
                new RuntimeException[] {
                    ProfileFailure.fromAuth(SignupFailure.unavailable()),
                    ProfileFailure.fromAuth(
                            new SignupFailure(429, "AUTH_RATE_LIMITED", "Please try again later.")),
                    ProfileFailure.fromAuth(SignupFailure.upstream()),
                    new IllegalStateException("private internal detail")
                }) {
            doThrow(failure).when(auth).verifyAccessToken("test-access");
            var response =
                    mvc.perform(
                                    patch(PATH)
                                            .header("Authorization", "Bearer test-access")
                                            .contentType("application/json")
                                            .content("{\"bio\":null}"))
                            .andExpect(
                                    status().is(
                                                    failure instanceof ProfileFailure profile
                                                            ? profile.status()
                                                            : 500))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString())
                    .doesNotContain("private internal detail", "test-access");
        }
        verifyNoInteractions(users);
    }

    @Test
    void rejectsDuplicateHeadersAndTrailingJson() throws Exception {
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer one", "Bearer two")
                                .contentType("application/json")
                                .content("{\"bio\":null}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        patch(PATH)
                                .header("Authorization", "Bearer test-access")
                                .contentType("application/json")
                                .content("{\"bio\":null} {\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @Test
    void boundsBodiesWithAndWithoutContentLengthAndChecksContentType() throws Exception {
        String body = "{\"bio\":\"" + "x".repeat(16384) + "\"}";
        for (boolean unknownLength : new boolean[] {false, true}) {
            mvc.perform(
                            patch(PATH)
                                    .header("Authorization", "Bearer test-access")
                                    .contentType("application/json")
                                    .content(body)
                                    .with(
                                            request -> {
                                                if (unknownLength) {
                                                    var streamed = spy(request);
                                                    doReturn(-1L)
                                                            .when(streamed)
                                                            .getContentLengthLong();
                                                    return streamed;
                                                }
                                                return request;
                                            }))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        }
        mvc.perform(patch(PATH).contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(auth, users);
    }

    @Test
    void allowsPatchCorsPreflight() throws Exception {
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
        verifyNoInteractions(auth, users);
    }
}
