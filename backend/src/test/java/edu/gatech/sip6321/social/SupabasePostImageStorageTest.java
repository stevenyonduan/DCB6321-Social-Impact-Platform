package edu.gatech.sip6321.social;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class SupabasePostImageStorageTest {
    HttpServer server;
    HttpClient client;
    SupabasePostImageStorage storage;
    SupabaseImageObjects objects;
    List<String> requests;
    int uploadStatus;
    byte[] downloadBytes;

    @BeforeEach
    void setup() throws Exception {
        requests = new ArrayList<>();
        uploadStatus = 200;
        downloadBytes = ValidatedImageTest.bytes("png");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer fake.service.jwt");
                    assertThat(exchange.getRequestHeaders().getFirst("apikey"))
                            .isEqualTo("fake.service.jwt");
                    if (exchange.getRequestMethod().equals("POST")) {
                        assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                                .isEqualTo("image/png");
                        assertThat(exchange.getRequestHeaders().getFirst("x-upsert"))
                                .isEqualTo("false");
                    }
                    exchange.getRequestBody().readAllBytes();
                    if (exchange.getRequestMethod().equals("GET")) {
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, 0);
                        try (var output = exchange.getResponseBody()) {
                            output.write(downloadBytes);
                        }
                        return;
                    }
                    int status = exchange.getRequestMethod().equals("POST") ? uploadStatus : 200;
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
                    exchange.sendResponseHeaders(status, 0);
                    exchange.getResponseBody().write("private upstream diagnostic".getBytes());
                    exchange.close();
                });
        server.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        objects =
                new SupabaseImageObjects(
                        client,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "fake.service.jwt");
        storage =
                new SupabasePostImageStorage(
                        client,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "social",
                        "fake.service.jwt");
    }

    @AfterEach
    void close() {
        client.close();
        server.stop(0);
    }

    @Test
    void generatedUploadReferenceMatchesDeleteAndIgnoresResponseBody() throws Exception {
        String ref = storage.upload(ValidatedImage.read(ValidatedImageTest.file("png")));
        assertThat(ref).matches("social/posts/[0-9a-f-]{36}\\.png");
        storage.delete(ref);
        assertThat(requests)
                .containsExactly(
                        "POST /storage/v1/object/" + ref, "DELETE /storage/v1/object/" + ref);
    }

    @Test
    void upstreamErrorsAreSanitizedAndCompensateGeneratedObject() throws Exception {
        for (int status : List.of(302, 400, 403, 500)) {
            requests.clear();
            uploadStatus = status;
            assertThatThrownBy(
                            () ->
                                    storage.upload(
                                            ValidatedImage.read(ValidatedImageTest.file("png"))))
                    .isInstanceOf(SocialFailure.class)
                    .hasMessage("Image storage is temporarily unavailable.");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(1)).isEqualTo(requests.get(0).replace("POST ", "DELETE "));
        }
    }

    @Test
    void unsafeOrForeignReferencesNeverReachNetwork() {
        for (String ref :
                List.of(
                        "https://evil.test/object",
                        "social/../other.png",
                        "other/posts/" + UUID.randomUUID() + ".png",
                        "social/posts/anything.png"))
            assertThatThrownBy(() -> storage.delete(ref)).isInstanceOf(SocialFailure.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void downloadsPrivateImageAndRejectsUnexpectedContentAndOversizedChunkedBodies() {
        String reference =
                "profile-avatars/avatars/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
        assertThat(objects.download(reference, "image/png")).isEqualTo(downloadBytes);
        assertThat(requests).containsExactly("GET /storage/v1/object/authenticated/" + reference);

        assertThatThrownBy(() -> objects.download(reference, "image/jpeg"))
                .isInstanceOf(SocialFailure.class);
        downloadBytes = new byte[ValidatedImage.MAX_BYTES + 1];
        assertThatThrownBy(() -> objects.download(reference, "image/png"))
                .isInstanceOf(SocialFailure.class);
    }

    @Test
    void disabledStorageDoesNotNeedCredentialsAndEnabledConfigurationFailsClosed() {
        var configuration = new SocialConfiguration();
        var disabled = configuration.postImageStorage(new MockEnvironment());
        assertThatThrownBy(
                        () ->
                                disabled.upload(
                                        new ValidatedImage(new byte[] {1}, "image/png", "png")))
                .isInstanceOf(SocialFailure.class);
        var enabled = new MockEnvironment().withProperty("SIP_STORAGE_ENABLED", "true");
        assertThatThrownBy(() -> configuration.postImageStorage(enabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("password");
    }
}
