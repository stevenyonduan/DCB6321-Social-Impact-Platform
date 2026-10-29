package edu.gatech.sip6321;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SIP_CORS_ALLOWED_ORIGINS=https://sip.example")
class HealthTest {
    @LocalServerPort private int port;

    @Test
    void healthRunsWithoutDatabaseCredentials() throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("application/json"));
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void exactOriginPreflightIsAllowedWithoutCookieCredentials() throws Exception {
        var response = preflight("https://sip.example");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("access-control-allow-origin"))
                .hasValue("https://sip.example");
        assertThat(response.headers().firstValue("access-control-allow-credentials")).isEmpty();
    }

    @Test
    void similarButUnapprovedOriginIsRejected() throws Exception {
        var response = preflight("https://sip.example.attacker.invalid");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
