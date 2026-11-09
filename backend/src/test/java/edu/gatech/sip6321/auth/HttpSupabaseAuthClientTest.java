package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.auth.SignupFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.gatech.sip6321.account.UserService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local stub HTTP only. No production Supabase host, keys, accounts, or mail. */
class HttpSupabaseAuthClientTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private HttpServer server;
    private HttpClient http;
    private final AtomicInteger signups = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final List<String> requests = new ArrayList<>();
    private int signupStatus = 200;
    private int userStatus = 200;
    private String signupBody;
    private String userBody;
    private String contentType = "application/json";

    @BeforeEach
    void start() throws IOException {
        signupBody = json.writeValueAsString(sessionBody());
        userBody = json.writeValueAsString(Map.of("id", ID, "email", EMAIL, "is_anonymous", false));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/auth/v1/signup",
                exchange -> {
                    signups.incrementAndGet();
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    assertThat(exchange.getRequestHeaders().getFirst("apikey"))
                            .isEqualTo("sb_publishable_test");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                    var body = json.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(body.propertyNames())
                            .containsExactlyInAnyOrder("email", "password", "data");
                    assertThat(body.path("password").asString()).isEqualTo(PASSWORD);
                    assertThat(body.path("data").propertyNames())
                            .containsExactlyInAnyOrder("first_name", "last_name");
                    respond(exchange, signupStatus, signupBody);
                });
        server.createContext(
                "/auth/v1/user",
                exchange -> {
                    lookups.incrementAndGet();
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer access-never-log");
                    assertThat(exchange.getRequestHeaders().getFirst("apikey"))
                            .isEqualTo("sb_publishable_test");
                    respond(exchange, userStatus, userBody);
                });
        server.createContext(
                "/auth/v1/token",
                exchange -> {
                    signups.incrementAndGet();
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    assertThat(exchange.getRequestURI().getRawQuery())
                            .isEqualTo("grant_type=password");
                    assertThat(exchange.getRequestHeaders().getFirst("apikey"))
                            .isEqualTo("sb_publishable_test");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                    var body = json.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(body.propertyNames()).containsExactlyInAnyOrder("email", "password");
                    assertThat(body.path("password").asString()).isEqualTo(PASSWORD);
                    respond(exchange, signupStatus, signupBody);
                });
        server.start();
        http =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(1))
                        .build();
    }

    @AfterEach
    void stop() {
        http.shutdownNow();
        server.stop(0);
        validators.close();
    }

    private HttpSupabaseAuthClient client(Duration timeout) {
        return new HttpSupabaseAuthClient(
                http,
                json,
                validators.getValidator(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "sb_publishable_test",
                timeout);
    }

    @Test
    void profileUsesTheAccessTokenReturnedByLoginAndVerifiesOncePerRequest() throws Exception {
        var auth = client(Duration.ofSeconds(2));
        var users = mock(UserService.class);
        when(users.getById(ID)).thenReturn(account());
        var login = new LoginService(auth, users).login(new LoginRequest(EMAIL, PASSWORD));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ProfileController(new ProfileService(auth, users)))
                        .setControllerAdvice(new ProfileExceptionHandler())
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/users/me")
                                .header("Authorization", "Bearer " + login.session().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(signups.get()).isEqualTo(1);
        assertThat(lookups.get()).isEqualTo(2);
        verify(users, times(2)).getById(ID);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad_jwt", "session_expired", "session_not_found"})
    void profileRejectsInvalidAndExpiredTokens(String code) {
        userStatus = 401;
        userBody = "{\"code\":\"" + code + "\",\"msg\":\"access-never-log\"}";
        assertThatThrownBy(
                        () -> client(Duration.ofSeconds(2)).verifyAccessToken("access-never-log"))
                .isInstanceOf(ProfileFailure.class)
                .hasNoCause()
                .extracting("status", "code")
                .containsExactly(401, "UNAUTHORIZED");
    }

    @Test
    void profileRejectsMalformedAndAnonymousIdentities() {
        for (String body :
                new String[] {
                    "{}", "{\"id\":\"invalid\"}", "{\"id\":\"" + ID + "\",\"is_anonymous\":true}"
                }) {
            userBody = body;
            assertThatThrownBy(
                            () ->
                                    client(Duration.ofSeconds(2))
                                            .verifyAccessToken("access-never-log"))
                    .isInstanceOf(ProfileFailure.class)
                    .hasNoCause();
        }
    }

    @Test
    void profileMapsUpstreamOutageAndNetworkFailureTo503() {
        userStatus = 503;
        assertProfileUnavailable(client(Duration.ofSeconds(2)));
        server.stop(0);
        assertProfileUnavailable(client(Duration.ofMillis(100)));
    }

    @Test
    void profileTimeoutIsBoundedAndReturns503() {
        server.removeContext("/auth/v1/user");
        server.createContext(
                "/auth/v1/user",
                exchange -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        assertProfileUnavailable(client(Duration.ofMillis(50)));
    }

    private void assertProfileUnavailable(HttpSupabaseAuthClient auth) {
        assertThatThrownBy(() -> auth.verifyAccessToken("access-never-log"))
                .isInstanceOf(ProfileFailure.class)
                .hasNoCause()
                .extracting("status", "code")
                .containsExactly(503, "AUTH_UNAVAILABLE");
    }

    private Map<String, Object> sessionBody() {
        var session = identity().session();
        return Map.of(
                "user",
                Map.of("id", ID, "email", EMAIL),
                "access_token",
                session.accessToken(),
                "refresh_token",
                session.refreshToken(),
                "token_type",
                "bearer",
                "expires_in",
                session.expiresIn(),
                "expires_at",
                session.expiresAt(),
                "untrusted_extra",
                "must-not-be-returned");
    }

    @Test
    void validatesSessionAgainstUserEndpointAndReturnsOnlyTrustedFields() {
        var result = client(Duration.ofSeconds(2)).signup(REQUEST);
        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.session().accessToken()).isEqualTo("access-never-log");
        assertThat(requests).containsExactly("POST /auth/v1/signup", "GET /auth/v1/user");
        assertThat(json.writeValueAsString(result)).doesNotContain("untrusted_extra", PASSWORD);
    }

    @ParameterizedTest
    @CsvSource({
        "400,user_already_exists,409",
        "422,email_exists,409",
        "422,weak_password,400",
        "400,email_address_invalid,400",
        "429,anything,429",
        "503,anything,503",
        "401,bad_key,502",
        "400,signup_disabled,502",
        "400,captcha_failed,502"
    })
    void upstreamFailuresAreClassifiedWithoutRawMessagesOrRetries(
            int upstream, String code, int expected) {
        signupStatus = upstream;
        signupBody =
                json.writeValueAsString(
                        Map.of("code", code, "msg", PASSWORD + " access-never-log"));
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining("access-never-log")
                .extracting("status")
                .isEqualTo(expected);
        assertThat(signups.get()).isEqualTo(1);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "400,user_already_exists,409",
        "422,email_exists,409",
        "422,weak_password,400",
        "400,email_address_invalid,400",
        "400,validation_failed,400",
        "400,captcha_failed,502"
    })
    void unversionedSignupErrorsUseSymbolicErrorCode(int upstream, String code, int expected) {
        signupStatus = upstream;
        signupBody =
                json.writeValueAsString(
                        Map.of(
                                "code",
                                upstream,
                                "error_code",
                                code,
                                "msg",
                                PASSWORD + " access-never-log"));
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining("access-never-log")
                .extracting("status")
                .isEqualTo(expected);
        assertThat(signups.get()).isEqualTo(1);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"code\":\"email_exists\",\"error_code\":null}",
                "{\"code\":\"weak_password\",\"error_code\":400}",
                "{\"code\":\"email_exists\",\"error_code\":[\"email_exists\"]}",
                "{\"code\":\"email_exists\",\"error_code\":\"captcha_failed\"}"
            })
    void invalidOrUnknownSignupErrorCodeCannotFallBackToRecognizedCode(String body) {
        signupStatus = 400;
        signupBody = body;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(502);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "access_token",
                "refresh_token",
                "token_type",
                "expires_in",
                "expires_at",
                "user"
            })
    void missingSessionOrIdentityIsNeverProofOfCreation(String field) {
        ObjectNode body = (ObjectNode) json.valueToTree(sessionBody());
        body.remove(field);
        signupBody = body.toString();
        invalidResponse();
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"user\":{\"id\":\"fake\",\"email\":\"student@example.test\"}}",
                "not json",
                "[]",
                "null"
            })
    void rejectsObfuscatedAndMalformedResponses(String body) {
        signupBody = body;
        invalidResponse();
    }

    @Test
    void mismatchedIdentityOrAnonymousUserCannotProvision() {
        for (Map<String, Object> body :
                List.<Map<String, Object>>of(
                        Map.of("id", "01234567-89ab-4cde-8fab-0123456789ab", "email", EMAIL),
                        Map.of("id", ID, "email", "different@example.test"),
                        Map.of("id", ID, "email", EMAIL, "is_anonymous", true),
                        Map.of("id", ID, "email", "invalid"))) {
            userBody = json.writeValueAsString(body);
            invalidResponse();
        }
    }

    @Test
    void rejectedAccessTokenIsAnUpstreamFailureWithNoIdentity() {
        userStatus = 401;
        userBody = "{\"code\":\"bad_jwt\",\"msg\":\"access-never-log\"}";
        invalidResponse();
    }

    @Test
    void permitsProviderEmailNormalizationButRequiresExactAgreementBetweenProviderResponses() {
        var request = new SignupRequest("Student@example.test", PASSWORD, "Jane", "Doe");
        // The stub returns the canonical provider email, not the request spelling.
        assertThat(client(Duration.ofSeconds(2)).signup(request).email()).isEqualTo(EMAIL);
    }

    @Test
    void rejectsOversizedBodyAndNonJsonAndRedirectWithoutFollowing() {
        signupBody = "x".repeat(65537);
        invalidResponse();
        contentType = "text/html";
        signupBody = "internal proxy details";
        invalidResponse();
        contentType = "application/json";
        signupStatus = 302;
        invalidResponse();
        assertThat(lookups.get()).isZero();
        assertThat(signups.get()).isEqualTo(3);
    }

    @Test
    void validatesTokenFormatAndExpiry() {
        for (Map.Entry<String, Object> invalid :
                Map.<String, Object>of(
                                "access_token",
                                "bad\r\nheader",
                                "refresh_token",
                                " ",
                                "token_type",
                                "cookie",
                                "expires_in",
                                -1,
                                "expires_at",
                                1)
                        .entrySet()) {
            ObjectNode body = (ObjectNode) json.valueToTree(sessionBody());
            body.set(invalid.getKey(), json.valueToTree(invalid.getValue()));
            signupBody = body.toString();
            invalidResponse();
        }
    }

    @Test
    void fullResponseBodyTimeoutIsBoundedAndDoesNotRepeatSignup() throws Exception {
        server.removeContext("/auth/v1/signup");
        {
            server.createContext(
                    "/auth/v1/signup",
                    exchange -> {
                        signups.incrementAndGet();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.close();
                    });
            assertThatThrownBy(() -> client(Duration.ofMillis(100)).signup(REQUEST))
                    .isInstanceOf(SignupFailure.class)
                    .hasNoCause()
                    .extracting("status")
                    .isEqualTo(503);
            assertThat(signups.get()).isEqualTo(1);
            assertThat(lookups.get()).isZero();
        }
    }

    private SupabaseAuthClient.VerifiedLogin login() {
        return client(Duration.ofSeconds(2)).login(new LoginRequest(EMAIL, PASSWORD));
    }

    @Test
    void loginUsesPasswordGrantAndTrustedUserLookup() {
        var result = login();
        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.session().refreshToken()).isEqualTo("refresh-never-log");
        assertThat(requests)
                .containsExactly("POST /auth/v1/token?grant_type=password", "GET /auth/v1/user");
        assertThat(result.toString()).doesNotContain("access-never-log", "refresh-never-log");
    }

    @ParameterizedTest
    @CsvSource({
        "400,invalid_credentials,401",
        "401,invalid_credentials,401",
        "422,invalid_credentials,401",
        "400,email_not_confirmed,401",
        "400,user_banned,401",
        "401,bad_key,502",
        "400,captcha_failed,502",
        "429,anything,429",
        "503,anything,503"
    })
    void loginProviderErrorsAreSanitized(int upstream, String code, int expected) {
        signupStatus = upstream;
        signupBody =
                json.writeValueAsString(
                        Map.of("code", code, "msg", PASSWORD + " access-never-log"));
        assertThatThrownBy(this::login)
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining("access-never-log")
                .extracting("status")
                .isEqualTo(expected);
        assertThat(signups.get()).isEqualTo(1);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "application/json",
                "application/json; charset=utf-8",
                "application/json;charset=UTF-8"
            })
    @ExtendWith(OutputCaptureExtension.class)
    void actualSupabaseErrorFormatsReturnGeneric401ThroughController(
            String type, CapturedOutput output) throws Exception {
        contentType = type;
        signupStatus = 400;
        var users = mock(UserService.class);
        var signup = mock(SignupService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthenticationController(
                                        signup,
                                        new LoginService(client(Duration.ofSeconds(2)), users)))
                        .setControllerAdvice(new SignupExceptionHandler())
                        .build();
        for (String body :
                List.of(
                        "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}",
                        "{\"code\":\"invalid_credentials\",\"message\":\"Invalid login credentials\"}")) {
            signupBody = body;
            for (String email : List.of(EMAIL, "unknown@example.test")) {
                // Repeat the same request, as in the reported production failure.
                for (int attempt = 0; attempt < 2; attempt++) {
                    mvc.perform(
                                    post("/api/auth/login")
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsBytes(
                                                            Map.of(
                                                                    "email",
                                                                    email,
                                                                    "password",
                                                                    PASSWORD))))
                            .andExpect(status().isUnauthorized())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    content()
                                            .json(
                                                    "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Invalid email or password.\"}"));
                }
            }
        }
        verifyNoInteractions(users, signup);
        assertThat(lookups.get()).isZero();
        assertThat(output.getAll())
                .doesNotContain(
                        PASSWORD,
                        "access-never-log",
                        "refresh-never-log",
                        "sb_publishable_test",
                        "Invalid login credentials",
                        "error_code");
    }

    @ParameterizedTest
    @CsvSource({
        "400,invalid_credentials,401",
        "401,invalid_credentials,401",
        "422,invalid_credentials,401",
        "400,email_not_confirmed,401",
        "400,user_banned,401",
        "400,validation_failed,502",
        "400,captcha_failed,502",
        "401,bad_key,502",
        "422,email_provider_disabled,502",
        "429,invalid_credentials,429",
        "503,invalid_credentials,503"
    })
    void initialSupabaseErrorFormatPreservesStatusAndMeaning(
            int upstream, String code, int expected) {
        signupStatus = upstream;
        signupBody =
                json.writeValueAsString(
                        Map.of(
                                "code",
                                upstream,
                                "error_code",
                                code,
                                "msg",
                                PASSWORD
                                        + " access-never-log refresh-never-log sb_publishable_test"));
        assertThatThrownBy(this::login)
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining("access-never-log")
                .hasMessageNotContaining("refresh-never-log")
                .hasMessageNotContaining("sb_publishable_test")
                .extracting("status")
                .isEqualTo(expected);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"code\":400,\"msg\":\"Invalid login credentials\"}",
                "{\"code\":400,\"error_code\":null}",
                "{\"code\":400,\"error_code\":401}",
                "{\"code\":400,\"error_code\":[\"invalid_credentials\"]}",
                "{\"code\":\"invalid_credentials\",\"error_code\":\"unexpected_failure\"}",
                "{\"code\":\"invalid_credentials\",\"error_code\":null}",
                "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid login credentials\"}",
                "{\"error_code\":\"invalid_credentials\"",
                "not json",
                "[]",
                "null",
                "{}"
            })
    void malformedOrUnrecognized400ResponsesStayUpstreamFailures(String body) {
        signupStatus = 400;
        signupBody = body;
        invalidLogin();
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html", "application/problem+json"})
    void credentialCodeInUnexpectedContentTypeIsNotTrusted(String type) {
        contentType = type;
        signupStatus = 400;
        signupBody =
                "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}";
        invalidLogin();
        assertThat(lookups.get()).isZero();
    }

    @Test
    void wrongPasswordAndUnknownEmailHaveIdenticalErrors() {
        signupStatus = 400;
        signupBody = "{\"code\":\"invalid_credentials\"}";
        for (String email : List.of(EMAIL, "unknown@example.test")) {
            assertThatThrownBy(
                            () ->
                                    client(Duration.ofSeconds(2))
                                            .login(new LoginRequest(email, PASSWORD)))
                    .isInstanceOf(LoginFailure.class)
                    .hasMessage("Invalid email or password.")
                    .extracting("code")
                    .isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "access_token",
                "refresh_token",
                "token_type",
                "expires_in",
                "expires_at",
                "user"
            })
    void loginRejectsIncompleteSession(String field) {
        ObjectNode body = (ObjectNode) json.valueToTree(sessionBody());
        body.remove(field);
        signupBody = body.toString();
        invalidLogin();
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "null", "{}"})
    void loginRejectsMalformedResponse(String body) {
        signupBody = body;
        invalidLogin();
    }

    @Test
    void loginRejectsUntrustedIdentitiesAndTokens() {
        for (Map<String, Object> body :
                List.<Map<String, Object>>of(
                        Map.of("id", "01234567-89ab-4cde-8fab-0123456789ab", "email", EMAIL),
                        Map.of("id", ID, "email", "other@example.test"),
                        Map.of("id", ID, "email", EMAIL, "is_anonymous", true))) {
            userBody = json.writeValueAsString(body);
            invalidLogin();
        }
        userStatus = 401;
        userBody = "{\"code\":\"invalid_credentials\"}";
        invalidLogin();
    }

    @Test
    void loginBoundsBodiesRejectsRedirectsAndValidatesSession() {
        for (Map.Entry<String, Object> invalid :
                Map.<String, Object>of(
                                "access_token",
                                "bad\r\nheader",
                                "refresh_token",
                                " ",
                                "token_type",
                                "cookie",
                                "expires_in",
                                -1,
                                "expires_at",
                                1)
                        .entrySet()) {
            ObjectNode body = (ObjectNode) json.valueToTree(sessionBody());
            body.set(invalid.getKey(), json.valueToTree(invalid.getValue()));
            signupBody = body.toString();
            invalidLogin();
        }
        signupBody = "x".repeat(65537);
        invalidLogin();
        contentType = "text/html";
        invalidLogin();
        contentType = "application/json";
        signupStatus = 302;
        invalidLogin();
        assertThat(lookups.get()).isZero();
    }

    @Test
    void loginNetworkFailureAndBodyTimeoutAreUnavailable() throws Exception {
        server.removeContext("/auth/v1/token");
        server.createContext(
                "/auth/v1/token",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        assertThatThrownBy(
                        () ->
                                client(Duration.ofMillis(100))
                                        .login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .extracting("status")
                .isEqualTo(503);
        server.stop(0);
        assertThatThrownBy(this::login)
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("AUTH_UNAVAILABLE");
        assertThat(lookups.get()).isZero();
    }

    private void invalidLogin() {
        assertThatThrownBy(this::login)
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("AUTH_RESPONSE_INVALID");
    }

    private void invalidResponse() {
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .extracting("status")
                .isEqualTo(502);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (status == 302) {
            exchange.getResponseHeaders().set("Location", "/auth/v1/user");
        }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
