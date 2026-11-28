package edu.gatech.sip6321.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.OrganizationManagementMigrations;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import edu.gatech.sip6321.social.SocialFailure;
import edu.gatech.sip6321.social.ValidatedImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.util.Arrays;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("database")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SIP_AUTH_ENABLED=true")
class ProfileAvatarPersistenceIT {
    private static final String PATH = "/api/users/me/avatar";
    private static final String TOKEN = "Bearer test-access";

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withInitScript("account-test-bootstrap.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("SIP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("SIP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("SIP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean ProfileAvatarStorage storage;
    UUID user;
    byte[] png;

    @BeforeAll
    static void migrate(@Autowired DataSource source) {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"),
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"),
                        new FileSystemResource("db/migrations/V003__create_events.sql"))
                .execute(source);
        OrganizationManagementMigrations.apply(source);
    }

    @BeforeEach
    void setup() throws Exception {
        user = account();
        when(auth.verifyAccessToken("test-access")).thenReturn(user);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            png = bytes.toByteArray();
        }
    }

    @Test
    void uploadsReadsReplacesAndRemovesOnlyTheVerifiedUsersPhoto() throws Exception {
        UUID other = account();
        var otherBefore = jdbc.queryForMap("SELECT * FROM public.users WHERE id=?", other);
        var before = jdbc.queryForMap("SELECT * FROM public.users WHERE id=?", user);
        String first = reference();
        String replacement = reference();
        when(storage.upload(eq(user), any())).thenReturn(first, replacement);
        when(storage.owns(user, first)).thenReturn(true);
        when(storage.owns(user, replacement)).thenReturn(true);
        when(storage.download(user, first))
                .thenReturn(new ProfileAvatarService.Photo(png, "image/png"));

        mvc.perform(get(PATH).header("Authorization", TOKEN)).andExpect(status().isNotFound());
        upload().andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value(first));
        mvc.perform(get(PATH).header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png));
        mvc.perform(get("/api/users/me").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(first));

        doAnswer(
                        invocation -> {
                            assertThat(savedAvatar()).isEqualTo(replacement);
                            return null;
                        })
                .when(storage)
                .delete(user, first);
        upload().andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").value(replacement));
        verify(storage).delete(user, first);

        doAnswer(
                        invocation -> {
                            assertThat(savedAvatar()).isNull();
                            return null;
                        })
                .when(storage)
                .delete(user, replacement);
        for (int call = 0; call < 2; call++) {
            mvc.perform(delete(PATH).header("Authorization", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.avatarUrl").isEmpty());
        }
        verify(storage).delete(user, replacement);
        assertThat(jdbc.queryForMap("SELECT * FROM public.users WHERE id=?", user))
                .isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM public.users WHERE id=?", other))
                .isEqualTo(otherBefore);
    }

    @Test
    void failedStorageUploadAndLegacyReferencesKeepTheirExistingSemantics() throws Exception {
        String legacy = "https://example.test/legacy-avatar.png";
        jdbc.update("UPDATE public.users SET avatar_url=? WHERE id=?", legacy, user);
        when(storage.upload(eq(user), any())).thenThrow(SocialFailure.storage());
        upload().andExpect(status().isServiceUnavailable());
        assertThat(savedAvatar()).isEqualTo(legacy);
        mvc.perform(get(PATH).header("Authorization", TOKEN)).andExpect(status().isNotFound());
        mvc.perform(delete(PATH).header("Authorization", TOKEN)).andExpect(status().isOk());
        assertThat(savedAvatar()).isNull();
        verify(storage, never()).delete(any(), any());
    }

    @Test
    void rolledBackReplacementDeletesOnlyTheNewObject() throws Exception {
        String old = reference();
        String replacement = reference();
        jdbc.update("UPDATE public.users SET avatar_url=? WHERE id=?", old, user);
        when(storage.upload(eq(user), any())).thenReturn(replacement);
        jdbc.execute(
                """
                CREATE FUNCTION public.reject_avatar_update_test() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated write failure'; END $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER reject_avatar_update BEFORE UPDATE OF avatar_url ON public.users
                FOR EACH ROW EXECUTE FUNCTION public.reject_avatar_update_test()
                """);
        try {
            upload().andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"));
            assertThat(savedAvatar()).isEqualTo(old);
            verify(storage).delete(user, replacement);
            verify(storage, never()).delete(user, old);
        } finally {
            jdbc.execute("DROP TRIGGER reject_avatar_update ON public.users");
            jdbc.execute("DROP FUNCTION public.reject_avatar_update_test()");
        }
    }

    @Test
    void rejectsMissingAuthenticationAndInvalidImagesWithoutTouchingStorage() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(file(png)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart(HttpMethod.PUT, PATH)
                                .file(file(new byte[] {1, 2, 3}))
                                .header("Authorization", TOKEN))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        verifyNoInteractions(storage);
    }

    @Test
    void realServletAcceptsAnAvatarAndBoundsFixedLengthAndChunkedUploads() throws Exception {
        byte[] tooLarge = Arrays.copyOf(png, ValidatedImage.MAX_BYTES + 1);
        try (var client = HttpClient.newHttpClient()) {
            byte[] bytes = multipartBody(tooLarge);
            for (var publisher :
                    java.util.List.of(
                            HttpRequest.BodyPublishers.ofByteArray(bytes),
                            HttpRequest.BodyPublishers.ofInputStream(
                                    () -> new java.io.ByteArrayInputStream(bytes)))) {
                var response = sendUpload(client, publisher);
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).contains("REQUEST_TOO_LARGE");
            }
            verifyNoInteractions(storage);

            String reference = reference();
            when(storage.upload(eq(user), any())).thenReturn(reference);
            var response =
                    sendUpload(client, HttpRequest.BodyPublishers.ofByteArray(multipartBody(png)));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(reference);
            assertThat(savedAvatar()).isEqualTo(reference);
        }
    }

    private byte[] multipartBody(byte[] image) throws java.io.IOException {
        try (var body = new ByteArrayOutputStream()) {
            body.write(
                    ("--avatar-test\r\nContent-Disposition: form-data; name=\"image\"; filename=\"test.png\"\r\n"
                                    + "Content-Type: image/png\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(image);
            body.write("\r\n--avatar-test--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return body.toByteArray();
        }
    }

    private HttpResponse<String> sendUpload(HttpClient client, HttpRequest.BodyPublisher body)
            throws java.io.IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                        .header("Authorization", TOKEN)
                        .header("Content-Type", "multipart/form-data; boundary=avatar-test")
                        .PUT(body)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private ResultActions upload() throws Exception {
        return mvc.perform(
                multipart(HttpMethod.PUT, PATH).file(file(png)).header("Authorization", TOKEN));
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("image", "profile.png", "image/png", bytes);
    }

    private String savedAvatar() {
        return jdbc.queryForObject(
                "SELECT avatar_url FROM public.users WHERE id=?", String.class, user);
    }

    private String reference() {
        return "profile-avatars/avatars/" + user + "/" + UUID.randomUUID() + ".png";
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update(
                """
                INSERT INTO public.users (id, email, role, first_name, last_name)
                VALUES (?, ?, 'STANDARD', 'Test', 'Account')
                """,
                id,
                id + "@example.test");
        return id;
    }
}
