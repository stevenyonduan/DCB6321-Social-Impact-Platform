package edu.gatech.sip6321.auth;

import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class HttpSupabaseAuthClient implements SupabaseAuthClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final Validator validator;
    private final URI base;
    private final String key;
    private final Duration timeout;

    HttpSupabaseAuthClient(
            HttpClient http,
            ObjectMapper json,
            Validator validator,
            URI base,
            String key,
            Duration timeout) {
        this.http = http;
        this.json = json;
        this.validator = validator;
        this.base = base;
        this.key = key;
        this.timeout = timeout;
    }

    @Override
    public VerifiedSignup signup(SignupRequest request) {
        var body =
                Map.of(
                        "email",
                        request.email(),
                        "password",
                        request.password(),
                        "data",
                        Map.of("first_name", request.firstName(), "last_name", request.lastName()));
        JsonNode signup =
                exchange(
                        builder("/auth/v1/signup")
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                                .build(),
                        true);
        return verifySession(signup);
    }

    @Override
    public VerifiedLogin login(LoginRequest request) {
        try {
            JsonNode session =
                    exchange(
                            builder("/auth/v1/token?grant_type=password")
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "email",
                                                                    request.email(),
                                                                    "password",
                                                                    request.password()))))
                                    .build(),
                            false,
                            true);
            var verified = verifySession(session);
            return new VerifiedLogin(verified.id(), verified.email(), verified.session());
        } catch (SignupFailure failure) {
            throw LoginFailure.fromAuth(failure);
        }
    }

    @Override
    public UUID verifyAccessToken(String accessToken) {
        try {
            JsonNode user =
                    exchange(
                            builder("/auth/v1/user")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .GET()
                                    .build(),
                            false,
                            false,
                            true);
            if (user.path("is_anonymous").asBoolean(false)) {
                throw ProfileFailure.unauthorized();
            }
            return identityId(user);
        } catch (SignupFailure failure) {
            throw ProfileFailure.fromAuth(failure);
        }
    }

    private VerifiedSignup verifySession(JsonNode signup) {
        JsonNode user = signup.path("user");
        UUID id = identityId(user);
        String email = identityEmail(user);
        String access = token(signup, "access_token");
        String refresh = token(signup, "refresh_token");
        if (!"bearer".equalsIgnoreCase(signup.path("token_type").asString(""))) {
            throw SignupFailure.upstream();
        }
        long expiresIn = positiveLong(signup, "expires_in");
        long expiresAt = positiveLong(signup, "expires_at");
        if (expiresAt <= Instant.now().getEpochSecond()) {
            throw SignupFailure.upstream();
        }
        JsonNode verified =
                exchange(
                        builder("/auth/v1/user")
                                .header("Authorization", "Bearer " + access)
                                .GET()
                                .build(),
                        false);
        if (!id.equals(identityId(verified))
                || !email.equals(identityEmail(verified))
                || verified.path("is_anonymous").asBoolean(false)) {
            throw SignupFailure.upstream();
        }
        return new VerifiedSignup(
                id, email, new Session(access, refresh, "bearer", expiresIn, expiresAt));
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("apikey", key)
                .header("Accept", "application/json");
    }

    private JsonNode exchange(HttpRequest request, boolean signup) {
        return exchange(request, signup, false);
    }

    private JsonNode exchange(HttpRequest request, boolean signup, boolean login) {
        return exchange(request, signup, login, false);
    }

    private JsonNode exchange(HttpRequest request, boolean signup, boolean login, boolean profile) {
        var pending = http.sendAsync(request, info -> new LimitedBodySubscriber());
        try {
            // Includes the entire response body, even if the peer sends headers then stalls.
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (profile && status == 401) {
                throw ProfileFailure.unauthorized();
            }
            if (status == 429) {
                throw new SignupFailure(429, "AUTH_RATE_LIMITED", "Please try again later.");
            }
            if (status >= 500) {
                throw SignupFailure.unavailable();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw SignupFailure.upstream();
            }
            JsonNode node;
            try {
                node = json.readTree(response.body());
            } catch (RuntimeException exception) {
                throw SignupFailure.upstream();
            }
            if (node == null || !node.isObject()) {
                throw SignupFailure.upstream();
            }
            if (status < 200 || status >= 300) {
                String code = node.path("code").asString("");
                // Unversioned Auth uses numeric code + symbolic error_code; newer responses
                // use symbolic code. A present error_code takes precedence, even if invalid.
                String loginCode =
                        node.has("error_code") ? node.path("error_code").asString("") : code;
                if (login
                        && (status == 400 || status == 401 || status == 422)
                        && Set.of("invalid_credentials", "email_not_confirmed", "user_banned")
                                .contains(loginCode)) {
                    throw LoginFailure.credentials();
                }
                if (signup && (status == 400 || status == 422)) {
                    if (Set.of("user_already_exists", "email_exists").contains(code)) {
                        throw SignupFailure.conflict();
                    }
                    if (Set.of(
                                    "weak_password",
                                    "email_address_invalid",
                                    "validation_failed",
                                    "email_address_not_authorized")
                            .contains(code)) {
                        throw SignupFailure.invalid();
                    }
                }
                // Invalid API keys, disabled signup, CAPTCHA requirements and failed session
                // validation are upstream/configuration failures, never client authentication.
                throw SignupFailure.upstream();
            }
            return node;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SignupFailure.unavailable();
        } catch (TimeoutException exception) {
            throw SignupFailure.unavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof SignupFailure failure) {
                throw failure;
            }
            throw SignupFailure.unavailable();
        } finally {
            pending.cancel(true);
        }
    }

    private UUID identityId(JsonNode user) {
        try {
            String value = user.path("id").asString("");
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw SignupFailure.upstream();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw SignupFailure.upstream();
        }
    }

    private String identityEmail(JsonNode user) {
        JsonNode value = user.path("email");
        if (!value.isString()) {
            throw SignupFailure.upstream();
        }
        String email = value.asString();
        if (!email.equals(email.strip())
                || email.indexOf(0) >= 0
                || !validator.validateValue(SignupRequest.class, "email", email).isEmpty()) {
            throw SignupFailure.upstream();
        }
        return email;
    }

    private String token(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString()
                || value.asString().isBlank()
                || value.asString().length() > 16384
                || value.asString().chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw SignupFailure.upstream();
        }
        return value.asString();
    }

    private long positiveLong(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw SignupFailure.upstream();
        }
        return value.asLong();
    }

    /** Bound bytes before buffering/parsing any upstream body, including error responses. */
    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65536 - received) {
                    subscription.cancel();
                    delegate.onError(SignupFailure.upstream());
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
