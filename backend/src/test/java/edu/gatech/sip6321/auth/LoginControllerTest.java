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
class LoginControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SignupService signup;
    @MockitoBean LoginService login;

    private Map<String, Object> valid() {
        return new HashMap<>(Map.of("email", EMAIL, "password", PASSWORD));
    }

    @Test
    void successUsesExistingAllowlistAndNoStore(CapturedOutput output) throws Exception {
        doReturn(SignupResponse.of(account(), identity().session())).when(login).login(any());
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(valid())))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.user.id").value(ID.toString()))
                        .andExpect(jsonPath("$.session.accessToken").value("access-never-log"))
                        .andExpect(jsonPath("$.session.refreshToken").value("refresh-never-log"))
                        .andExpect(jsonPath("$.session.tokenType").value("bearer"))
                        .andExpect(jsonPath("$.session.expiresIn").value(3600))
                        .andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("user", "session");
        assertThat(body.path("session").propertyNames())
                .containsExactlyInAnyOrder(
                        "accessToken", "refreshToken", "tokenType", "expiresIn", "expiresAt");
        assertThat(body.path("user").propertyNames())
                .containsExactlyInAnyOrder("id", "email", "role", "firstName", "lastName");
        assertThat(body.toString()).doesNotContain(PASSWORD);
        assertThat(output.getAll())
                .doesNotContain(PASSWORD, "access-never-log", "refresh-never-log");
        verify(login).login(new LoginRequest(EMAIL, PASSWORD));
        verifyNoInteractions(signup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "password"})
    void rejectsInvalidFields(String field, CapturedOutput output) throws Exception {
        var body = valid();
        body.remove(field);
        invalid(body);
        for (Object value :
                new Object[] {
                    null,
                    "",
                    " ",
                    42,
                    true,
                    Map.of("x", "y"),
                    "bad\u0000value",
                    "bad\uD800value",
                    "x".repeat(field.equals("email") ? 256 : 1025)
                }) {
            body.put(field, value);
            invalid(body);
        }
        verifyNoInteractions(login, signup);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "invalid",
                " student@example.test",
                "student@example.test ",
                "a\n@example.test"
            })
    void rejectsMalformedEmail(String email) throws Exception {
        var body = valid();
        body.put("email", email);
        invalid(body);
        verifyNoInteractions(login);
    }

    @Test
    void requestSafeguardsApplyToLogin() throws Exception {
        var body = valid();
        body.put("role", "ADMIN");
        invalid(body);
        for (String raw : new String[] {"{bad", "[]", "null", ""}) {
            mvc.perform(post("/api/auth/login").contentType("application/json").content(raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(
                        post("/api/auth/login")
                                .contentType("application/json")
                                .content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/auth/login").contentType("text/plain").content(PASSWORD))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/auth/login")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(login);
    }

    @Test
    void errorsAreSanitized(CapturedOutput output) throws Exception {
        for (LoginFailure failure :
                new LoginFailure[] {
                    LoginFailure.credentials(),
                    LoginFailure.provisioning(),
                    LoginFailure.database(),
                    LoginFailure.fromAuth(SignupFailure.unavailable()),
                    LoginFailure.fromAuth(SignupFailure.upstream())
                }) {
            doThrow(failure).when(login).login(any());
            mvc.perform(
                            post("/api/auth/login")
                                    .contentType("application/json")
                                    .content(json.writeValueAsBytes(valid())))
                    .andExpect(status().is(failure.status()))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(failure.code()))
                    .andExpect(jsonPath("$.session").doesNotExist());
        }
        doThrow(new IllegalStateException(PASSWORD + " access-never-log refresh-never-log"))
                .when(login)
                .login(any());
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(valid())))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn();
        assertThat(output.getAll() + result.getResponse().getContentAsString())
                .doesNotContain(PASSWORD, "access-never-log", "refresh-never-log");
    }

    private void invalid(Map<String, Object> body) throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
