package edu.gatech.sip6321.social;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.OrganizationManagementMigrations;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("database")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "SIP_AUTH_ENABLED=true")
class SocialPersistenceIT {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    java.net.http.HttpResponse<String> uploadHttp(byte[] image, boolean chunked) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(
                ("--sip-boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(image);
        bytes.write("\r\n--sip-boundary--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] body = bytes.toByteArray();
        var publisher =
                chunked
                        ? java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                                () -> new java.io.ByteArrayInputStream(body))
                        : java.net.http.HttpRequest.BodyPublishers.ofByteArray(body);
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + port + "/api/posts"))
                            .header("Authorization", "Bearer first")
                            .header("Content-Type", "multipart/form-data; boundary=sip-boundary")
                            .POST(publisher)
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void realServletAcceptsInclusiveLimitAndRejectsOversizeIncludingChunked() throws Exception {
        byte[] valid =
                java.util.Arrays.copyOf(ValidatedImageTest.bytes("png"), ValidatedImage.MAX_BYTES);
        assertThat(uploadHttp(valid, false).statusCode()).isEqualTo(201);
        for (boolean chunked : List.of(false, true)) {
            var response = uploadHttp(java.util.Arrays.copyOf(valid, valid.length + 1), chunked);
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body())
                    .contains("REQUEST_TOO_LARGE")
                    .doesNotContain("Exception", "Tomcat", "5242880");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    // The JUnit Testcontainers extension owns and closes this shared container.
    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withInitScript("account-test-bootstrap.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("SIP_DB_URL", POSTGRES::getJdbcUrl);
        r.add("SIP_DB_USERNAME", POSTGRES::getUsername);
        r.add("SIP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired DataSource source;
    @Autowired SocialService social;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean PostImageStorage storage;
    JdbcTemplate jdbc;
    UUID user, other;

    static List<String> h(String token) {
        return List.of("Bearer " + token);
    }

    @BeforeAll
    static void migrate(@Autowired DataSource source) throws Exception {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"),
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"),
                        new FileSystemResource("db/migrations/V003__create_events.sql"))
                .execute(source);
        OrganizationManagementMigrations.apply(source);
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    Files.readString(
                            Path.of("db/migrations/V005__create_event_registrations.sql")));
            var jdbc = new JdbcTemplate(source);
            UUID existing = UUID.randomUUID();
            jdbc.update("INSERT INTO auth.users VALUES (?)", existing);
            jdbc.update(
                    "INSERT INTO public.users(id,email,role,first_name,last_name) VALUES (?,'before-social@example.test','ADMIN','Existing','Account')",
                    existing);
            var before = jdbc.queryForList("SELECT * FROM public.users");
            s.execute(Files.readString(Path.of("db/migrations/V006__create_social_posts.sql")));
            assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(before);
        }
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE public.posts CASCADE");
        user = account("first");
        other = account("second");
        when(storage.upload(any())).thenAnswer(inv -> "bucket/posts/" + UUID.randomUUID() + ".png");
    }

    UUID account(String token) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", id);
        jdbc.update(
                "INSERT INTO public.users(id,email,role,first_name,last_name) VALUES (?,?,'STANDARD','First','Last')",
                id,
                id + "@example.test");
        when(auth.verifyAccessToken(token)).thenReturn(id);
        return id;
    }

    PostResponse create(String text) {
        return social.create(h("first"), text, null);
    }

    String path(UUID id) {
        return "/api/posts/" + id;
    }

    @Test
    void completeTextPostInteractionCommentAndSavedFlow() throws Exception {
        var p = create("Food bank");
        assertThat(p.authorId()).isEqualTo(user);
        assertThat(p.organizationId()).isNull();
        assertThat(p.createdAt()).isEqualTo(p.updatedAt());
        for (String type : List.of("LIKE", "SAVE"))
            for (int i = 0; i < 2; i++) social.interaction(h("second"), p.id(), type, true);
        var c = social.comment(h("second"), p.id(), "Thank you");
        var read = social.get(h("second"), p.id());
        assertThat(read.likeCount()).isEqualTo(1);
        assertThat(read.commentCount()).isEqualTo(1);
        assertThat(read.liked()).isTrue();
        assertThat(read.saved()).isTrue();
        assertThat(social.get(h("first"), p.id()).saved()).isFalse();
        assertThat(social.feed(h("first"), 0, 20, true).items()).isEmpty();
        assertThat(social.feed(h("second"), 0, 20, true).items()).hasSize(1);
        assertThat(social.comments(h("first"), p.id(), 0, 20).items()).containsExactly(c);
        var edited = social.patch(h("first"), p.id(), true, "Edited");
        assertThat(edited.updatedAt()).isAfter(p.updatedAt());
        assertThat(edited.createdAt()).isEqualTo(p.createdAt());
        assertThat(social.patch(h("first"), p.id(), false, null).textContent()).isEqualTo("Edited");
        assertThatThrownBy(() -> social.patch(h("first"), p.id(), true, null))
                .isInstanceOf(SocialFailure.class);
        assertThat(social.get(h("first"), p.id()).textContent()).isEqualTo("Edited");
        for (String type : List.of("LIKE", "SAVE"))
            for (int i = 0; i < 2; i++) social.interaction(h("second"), p.id(), type, false);
        assertThat(social.get(h("second"), p.id()).likeCount()).isZero();
        social.interaction(h("second"), p.id(), "SAVE", true);
        social.delete(h("first"), p.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.post_comments", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.post_interactions", Integer.class))
                .isZero();
        assertThat(social.feed(h("second"), 0, 20, true).items()).isEmpty();
        mvc.perform(get(path(p.id())).header("Authorization", "Bearer second"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerAdminAndGlobalRolesRetainPersonalVolunteerAccess() {
        for (String global : List.of("STANDARD", "ADMIN", "ORGANIZATION")) {
            jdbc.update("UPDATE public.users SET role=? WHERE id=?", global, user);
            assertThat(create("Personal").organizationId()).isNull();
        }
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO public.organizations(name) VALUES ('Group') RETURNING id",
                        UUID.class);
        for (String role : List.of("OWNER", "ADMIN")) {
            jdbc.update(
                    "UPDATE public.users SET organization_id=?,organization_role=? WHERE id=?",
                    org,
                    role,
                    user);
            var p = create("Still personal");
            assertThat(p.organizationId()).isNull();
            social.delete(h("first"), p.id());
        }
    }

    @Test
    void ownershipAndCommentPathCannotBeSpoofed() throws Exception {
        var p = create("A");
        var b = create("B");
        var c = social.comment(h("second"), b.id(), "Comment");
        for (var request :
                List.of(
                        patch(path(p.id()))
                                .contentType("application/json")
                                .content("{\"textContent\":\"x\"}"),
                        delete(path(p.id()))))
            mvc.perform(request.header("Authorization", "Bearer second"))
                    .andExpect(status().isForbidden());
        mvc.perform(
                        delete(path(p.id()) + "/comments/" + c.id())
                                .header("Authorization", "Bearer second"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete(path(b.id()) + "/comments/" + c.id())
                                .header("Authorization", "Bearer first"))
                .andExpect(status().isForbidden());
        social.deleteComment(h("second"), b.id(), c.id());
        assertThat(social.get(h("first"), b.id()).commentCount()).isZero();
        assertThatThrownBy(() -> social.comment(h("first"), UUID.randomUUID(), "Hi"))
                .isInstanceOf(SocialFailure.class);
    }

    @Test
    void imageOnlyMultimediaReplacementRemovalAndTextOnlyDuringStorageOutage() throws Exception {
        var image = social.create(h("first"), null, ValidatedImageTest.file("png"));
        assertThat(image.textContent()).isNull();
        assertThat(image.imageReference()).startsWith("bucket/posts/");
        assertThatThrownBy(() -> social.image(h("first"), image.id(), null, true))
                .isInstanceOf(SocialFailure.class);
        mvc.perform(
                        multipart(path(image.id()) + "/image")
                                .file(ValidatedImageTest.file("jpg"))
                                .with(
                                        r -> {
                                            r.setMethod("PUT");
                                            return r;
                                        })
                                .header("Authorization", "Bearer second"))
                .andExpect(status().isForbidden());
        var replaced = social.image(h("first"), image.id(), ValidatedImageTest.file("jpg"), false);
        assertThat(replaced.imageReference()).isNotEqualTo(image.imageReference());
        verify(storage).delete(image.imageReference());
        social.patch(h("first"), image.id(), true, "Caption");
        var removed = social.image(h("first"), image.id(), null, true);
        assertThat(removed.imageReference()).isNull();
        assertThat(removed.textContent()).isEqualTo("Caption");
        assertThat(social.create(h("first"), "Both", ValidatedImageTest.file("png")).textContent())
                .isEqualTo("Both");
        when(storage.upload(any())).thenThrow(SocialFailure.storage());
        assertThat(create("Storage independent").textContent()).isEqualTo("Storage independent");
    }

    @Test
    void databaseFailureAndOuterTransactionRollbackCompensateUpload() throws Exception {
        jdbc.execute(
                "CREATE FUNCTION public.reject_social() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private SQL' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_social AFTER INSERT ON public.posts FOR EACH ROW EXECUTE FUNCTION public.reject_social()");
        when(storage.upload(any())).thenReturn("bucket/posts/new.png");
        try {
            mvc.perform(
                            multipart("/api/posts")
                                    .file(ValidatedImageTest.file("png"))
                                    .header("Authorization", "Bearer first"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SOCIAL_UNAVAILABLE"));
            verify(storage).delete("bucket/posts/new.png");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.posts", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_social ON public.posts");
            jdbc.execute("DROP FUNCTION public.reject_social()");
        }
        clearInvocations(storage);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            try {
                                social.create(h("first"), null, ValidatedImageTest.file("png"));
                                status.setRollbackOnly();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        verify(storage).delete("bucket/posts/new.png");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.posts", Integer.class))
                .isZero();
    }

    @Test
    void stableSqlPaginationAndCallerSpecificSavedOrdering() {
        for (int i = 0; i < 5; i++) {
            var p = create("Post " + i);
            social.interaction(h("first"), p.id(), "SAVE", true);
            social.comment(h("first"), p.id(), "Comment");
        }
        jdbc.update(
                "UPDATE public.posts SET created_at=?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        var expected =
                jdbc.queryForList(
                        "SELECT id FROM public.posts ORDER BY created_at DESC,id DESC", UUID.class);
        var actual = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) {
            var slice = social.feed(h("first"), page, 2, false);
            actual.addAll(slice.items().stream().map(PostResponse::id).toList());
            assertThat(slice.hasNext()).isEqualTo(page < 2);
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        jdbc.execute("UPDATE public.post_interactions SET created_at='2026-01-01'");
        actual.clear();
        for (int page = 0; page < 3; page++)
            actual.addAll(
                    social.feed(h("first"), page, 2, true).items().stream()
                            .map(PostResponse::id)
                            .toList());
        assertThat(actual).containsExactlyElementsOf(expected);
        var p = create("Many comments");
        for (int i = 0; i < 5; i++) social.comment(h("first"), p.id(), "C" + i);
        jdbc.execute("UPDATE public.post_comments SET created_at='2026-01-01'");
        var commentIds =
                jdbc.queryForList(
                        "SELECT id FROM public.post_comments WHERE post_id=? ORDER BY created_at,id",
                        UUID.class,
                        p.id());
        actual.clear();
        for (int page = 0; page < 3; page++)
            actual.addAll(
                    social.comments(h("second"), p.id(), page, 2).items().stream()
                            .map(PostComment.CommentResponse::id)
                            .toList());
        assertThat(actual).containsExactlyElementsOf(commentIds);
    }

    @Test
    void concurrentInteractionsAreIdempotentAndCoexist() throws Exception {
        var p = create("Race");
        var barrier = new CyclicBarrier(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                final String type = i % 2 == 0 ? "LIKE" : "SAVE";
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    social.interaction(h("first"), p.id(), type, true);
                                    return null;
                                }));
            }
            for (var f : futures) f.get(20, TimeUnit.SECONDS);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.post_interactions", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void deletionRacingWithChildWritesLeavesNoOrphans() throws Exception {
        var p = create("Concurrent delete");
        var barrier = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var delete =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                social.delete(h("first"), p.id());
                                return null;
                            });
            var like =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                try {
                                    social.interaction(h("second"), p.id(), "LIKE", true);
                                } catch (SocialFailure f) {
                                    assertThat(f.code()).isEqualTo("POST_NOT_FOUND");
                                }
                                return null;
                            });
            var comment =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                try {
                                    social.comment(h("second"), p.id(), "Race");
                                } catch (SocialFailure f) {
                                    assertThat(f.code()).isEqualTo("POST_NOT_FOUND");
                                }
                                return null;
                            });
            for (var f : List.of(delete, like, comment)) f.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.post_comments", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.post_interactions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.posts", Integer.class))
                .isZero();
    }

    @Test
    void rejectedCommentBodiesCannotWriteOrSpoofAuthors() throws Exception {
        var p = create("Comments");
        for (String body :
                List.of(
                        "{}",
                        "{\"content\":null}",
                        "{\"content\":42}",
                        "{\"content\":\" \"}",
                        "{\"content\":\"Hi\",\"authorId\":null}")) {
            mvc.perform(
                            post(path(p.id()) + "/comments")
                                    .header("Authorization", "Bearer second")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        post(path(p.id()) + "/comments")
                                .header("Authorization", "Bearer second")
                                .contentType("application/json")
                                .content("{\"content\":\"Hi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId").value(other.toString()));
        assertThat(social.get(h("first"), p.id()).commentCount()).isEqualTo(1);
    }

    @Test
    void socialActionsPreserveAllAccountFieldsAndHideGroupRows() {
        var before = jdbc.queryForList("SELECT * FROM public.users ORDER BY id");
        var p = create("Personal");
        social.interaction(h("first"), p.id(), "LIKE", true);
        social.interaction(h("first"), p.id(), "SAVE", true);
        social.comment(h("first"), p.id(), "Comment");
        social.patch(h("first"), p.id(), true, "Edited");
        assertThat(jdbc.queryForList("SELECT * FROM public.users ORDER BY id")).isEqualTo(before);
        UUID org =
                jdbc.queryForObject(
                        "INSERT INTO public.organizations(name) VALUES ('Future') RETURNING id",
                        UUID.class);
        jdbc.update("UPDATE public.posts SET organization_id=? WHERE id=?", org, p.id());
        assertThat(social.feed(h("first"), 0, 20, false).items()).isEmpty();
        assertThat(social.feed(h("first"), 0, 20, true).items()).isEmpty();
        assertThatThrownBy(() -> social.get(h("first"), p.id()))
                .isInstanceOf(SocialFailure.class)
                .extracting("status")
                .isEqualTo(404);
        assertThatThrownBy(() -> social.delete(h("first"), p.id()))
                .isInstanceOf(SocialFailure.class)
                .extracting("status")
                .isEqualTo(404);
    }

    @Test
    void schemaKeysDefaultsRlsPrivilegesAndCascades() {
        var p = create("Schema");
        assertThat(p.id().version()).isEqualTo(4);
        jdbc.update(
                "INSERT INTO public.post_interactions(user_id,post_id,interaction_type) VALUES (?,?,'LIKE')",
                user,
                p.id());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO public.post_interactions(user_id,post_id,interaction_type) VALUES (?,?,'LIKE')",
                                        user,
                                        p.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO public.post_interactions(user_id,post_id,interaction_type) VALUES (?,?,'SHARE')",
                                        user,
                                        p.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO public.post_comments(post_id,author_id,content) VALUES (?,?,'x')",
                                        UUID.randomUUID(),
                                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO public.posts(author_id,text_content) VALUES (?,'x')",
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String table : List.of("posts", "post_interactions", "post_comments")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT relrowsecurity FROM pg_class WHERE oid=?::regclass",
                                    Boolean.class,
                                    "public." + table))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename=?",
                                    Integer.class,
                                    table))
                    .isZero();
            for (String role : List.of("anon", "authenticated"))
                for (String privilege :
                        List.of(
                                "SELECT",
                                "INSERT",
                                "UPDATE",
                                "DELETE",
                                "TRUNCATE",
                                "REFERENCES",
                                "TRIGGER"))
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT has_table_privilege(?,?,?)",
                                            Boolean.class,
                                            role,
                                            "public." + table,
                                            privilege))
                            .isFalse();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid IN ('public.post_comments'::regclass,'public.post_interactions'::regclass) AND contype='f' AND confdeltype='c'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE schemaname='public'",
                                String.class))
                .contains(
                        "posts_feed_idx",
                        "post_comments_page_idx",
                        "post_interactions_counts_idx",
                        "post_interactions_personal_idx");
    }
}
