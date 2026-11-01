package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.auth.SignupFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuthenticationController.class, properties = "SIP_AUTH_ENABLED=true")
@Import({SignupExceptionHandler.class, SignupRequestFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SignupService service;

    private Map<String, Object> valid() {
        return new HashMap<>(
                Map.of(
                        "email",
                        EMAIL,
                        "password",
                        PASSWORD,
                        "firstName",
                        "Jane",
                        "lastName",
                        "Doe"));
    }

    @Test
    void successIsAllowlistedAndNotCached() throws Exception {
        doReturn(SignupResponse.of(account(), identity().session())).when(service).signup(any());
        var result =
                mvc.perform(
                                post("/api/auth/signup")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(valid())))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.user.id").value(ID.toString()))
                        .andExpect(jsonPath("$.user.role").value("STANDARD"))
                        .andExpect(jsonPath("$.session.accessToken").value("access-never-log"))
                        .andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("user", "session");
        assertThat(body.path("user").propertyNames())
                .containsExactlyInAnyOrder("id", "email", "role", "firstName", "lastName");
        assertThat(body.path("session").propertyNames())
                .containsExactlyInAnyOrder(
                        "accessToken", "refreshToken", "tokenType", "expiresIn", "expiresAt");
        assertThat(body.toString()).doesNotContain(PASSWORD, "organizationId", "app_metadata");
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password", "firstName", "lastName"})
    void requiredFieldsRejectMissingNullBlankAndNonString(String field) throws Exception {
        for (Object value : new Object[] {null, "", "  ", 42, true, Map.of("value", "x")}) {
            var body = valid();
            body.put(field, value);
            invalid(body);
        }
        var body = valid();
        body.remove(field);
        invalid(body);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-email",
                " student@example.test",
                "student@example.test ",
                "a\n@example.test"
            })
    void rejectsInvalidEmail(String email) throws Exception {
        var body = valid();
        body.put("email", email);
        invalid(body);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "id", "organizationId", "createdAt", "unexpected"})
    void rejectsAllUnknownProperties(String field) throws Exception {
        var body = valid();
        body.put(field, "ADMIN");
        invalid(body);
        verifyNoInteractions(service);
    }

    @Test
    void boundsValuesAndRejectsNulAndUnpairedSurrogates() throws Exception {
        for (String field : valid().keySet()) {
            var body = valid();
            body.put(
                    field,
                    "x"
                            .repeat(
                                    field.equals("password")
                                            ? 1025
                                            : field.equals("email") ? 256 : 101));
            invalid(body);
            body.put(field, "bad\u0000value");
            invalid(body);
            body.put(field, "bad\uD800value");
            invalid(body);
        }
        verifyNoInteractions(service);
    }

    @Test
    void delegatesPasswordPolicyAndAcceptsUnicodeColumnLimitsWithoutNormalizing() throws Exception {
        var body = valid();
        body.put("password", "x"); // Supabase, not the backend, owns minimum strength policy.
        body.put("firstName", "\uD83C\uDF31".repeat(100));
        body.put("lastName", " Doe ");
        body.put("email", "Student@example.test");
        doReturn(SignupResponse.of(account(), identity().session())).when(service).signup(any());
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isCreated());
        verify(service)
                .signup(
                        new SignupRequest(
                                "Student@example.test", "x", "\uD83C\uDF31".repeat(100), " Doe "));
    }

    @Test
    void malformedAndOversizedRequestsAreSafe(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType("application/json")
                                .content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        var body = valid();
        body.put("email", "invalid");
        invalid(body);
        assertThat(output.getAll())
                .doesNotContain(PASSWORD, "access-never-log", "refresh-never-log");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 429, 502, 503})
    void mappedErrorsNeverContainSessions(int status) throws Exception {
        when(service.signup(any()))
                .thenThrow(new SignupFailure(status, "SAFE_CODE", "Safe message."));
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(valid())))
                .andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        content().json("{\"code\":\"SAFE_CODE\",\"message\":\"Safe message.\"}"));
    }

    @Test
    void unexpectedFailuresDoNotExposeExceptionOrCause(CapturedOutput output) throws Exception {
        when(service.signup(any()))
                .thenThrow(
                        new IllegalStateException(
                                PASSWORD + " secret", new RuntimeException("refresh-never-log")));
        var result =
                mvc.perform(
                                post("/api/auth/signup")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(valid())))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString() + output.getAll())
                .doesNotContain(PASSWORD, "secret", "refresh-never-log");
    }

    private void invalid(Map<String, Object> body) throws Exception {
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        content()
                                .json(
                                        "{\"code\":\"INVALID_REQUEST\",\"message\":\"Check the signup fields and try again.\"}"));
    }

    @Test
    void unsupportedMethodsAndMediaTypesHaveSafeErrors() throws Exception {
        mvc.perform(get("/api/auth/signup"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/auth/signup").contentType("text/plain").content(PASSWORD))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }
}
