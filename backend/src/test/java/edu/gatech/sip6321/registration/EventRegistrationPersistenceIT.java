package edu.gatech.sip6321.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.OrganizationManagementMigrations;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import edu.gatech.sip6321.opportunity.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("database")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "SIP_AUTH_ENABLED=true")
class EventRegistrationPersistenceIT {
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

    @Autowired DataSource source;
    @Autowired EventRegistrationService service;
    @Autowired OpportunityService opportunities;
    @Autowired EventRegistrationRepository rows;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean Clock clock;
    static final Instant NOW = Instant.parse("2027-01-01T00:00:00.123456789Z");
    JdbcTemplate jdbc;
    UUID org, otherOrg, owner, admin, volunteer, otherVolunteer, otherOwner, event;

    @BeforeAll
    static void migrate(@Autowired DataSource source) throws Exception {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"),
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"),
                        new FileSystemResource("db/migrations/V003__create_events.sql"))
                .execute(source);
        OrganizationManagementMigrations.apply(source);
        var jdbc = new JdbcTemplate(source);
        // V005 must preserve existing organizations, users and events, not only an empty schema.
        var org = UUID.randomUUID();
        var user = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", user);
        jdbc.update("INSERT INTO public.organizations(id,name) VALUES (?, 'Existing')", org);
        jdbc.update(
                "INSERT INTO public.users(id,email,role,first_name,last_name,organization_id,organization_role) VALUES (?,'existing@example.test','STANDARD','Existing','Owner',?,'OWNER')",
                user,
                org);
        jdbc.update(
                "INSERT INTO public.events(organization_id,creator_id,title,description,event_start,event_end,location) VALUES (?,?,'Existing','Description',NOW()+interval '1 day',NOW()+interval '2 days','Atlanta')",
                org,
                user);
        var users = jdbc.queryForList("SELECT * FROM public.users");
        var orgs = jdbc.queryForList("SELECT * FROM public.organizations");
        var events = jdbc.queryForList("SELECT * FROM public.events");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    Files.readString(
                            Path.of("db/migrations/V005__create_event_registrations.sql")));
        }
        assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(users);
        assertThat(jdbc.queryForList("SELECT * FROM public.organizations")).isEqualTo(orgs);
        assertThat(jdbc.queryForList("SELECT * FROM public.events")).isEqualTo(events);
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM public.event_registrations");
        jdbc.update("DELETE FROM public.organization_management_audit");
        jdbc.update("DELETE FROM public.events");
        jdbc.update("DELETE FROM public.users");
        jdbc.update("DELETE FROM public.organizations");
        jdbc.update("DELETE FROM auth.users");
        when(clock.instant()).thenReturn(NOW);
        org = organization();
        otherOrg = organization();
        owner = account("owner", org, "OWNER");
        admin = account("admin", org, "ADMIN");
        volunteer = account("volunteer", null, null);
        otherVolunteer = account("other-volunteer", null, null);
        otherOwner = account("other-owner", otherOrg, "OWNER");
        event = publish("owner", RegistrationType.INTERNAL);
    }

    UUID organization() {
        return jdbc.queryForObject(
                "INSERT INTO public.organizations(name) VALUES ('Demo') RETURNING id", UUID.class);
    }

    UUID account(String token, UUID org, String role) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", id);
        jdbc.update(
                "INSERT INTO public.users(id,email,role,first_name,last_name,organization_id,organization_role) VALUES (?,?,'STANDARD',?,'Volunteer',?,?)",
                id,
                token + "@example.test",
                token,
                org,
                role);
        when(auth.verifyAccessToken(token)).thenReturn(id);
        return id;
    }

    List<String> headers(String token) {
        return List.of("Bearer " + token);
    }

    String path(UUID id) {
        return "/api/opportunities/" + id + "/registrations/me";
    }

    String attendees(UUID id) {
        return "/api/organizations/me/opportunities/" + id + "/registrations";
    }

    UUID publish(String token, RegistrationType type) {
        return opportunities
                .publish(
                        headers(token),
                        new PublishOpportunityRequest(
                                "Cleanup",
                                "Help",
                                null,
                                NOW.plusSeconds(3600),
                                NOW.plusSeconds(7200),
                                "Atlanta",
                                type,
                                type == RegistrationType.INTERNAL
                                        ? null
                                        : "https://example.test/register"))
                .id();
    }

    @Test
    void completeDemoPersistsReadsDuplicateAttendeesCancelsAndReregisters() throws Exception {
        var before = Instant.now().minusSeconds(1);
        var result =
                mvc.perform(post(path(event)).header("Authorization", "Bearer volunteer"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.userId").value(volunteer.toString()))
                        .andExpect(jsonPath("$.eventId").value(event.toString()))
                        .andReturn();
        var response = json.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(response.get("id").asString());
        Instant registeredAt = Instant.parse(response.get("registeredAt").asString());
        assertThat(registeredAt).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(registeredAt.getNano() % 1000).isZero();
        assertThat(rows.findById(id).orElseThrow().response())
                .isEqualTo(new EventRegistrationResponse(id, event, volunteer, registeredAt));
        mvc.perform(get(path(event)).header("Authorization", "Bearer volunteer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.registration.id").value(id.toString()));
        mvc.perform(post(path(event)).header("Authorization", "Bearer volunteer"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"));
        for (String manager : List.of("owner", "admin"))
            mvc.perform(get(attendees(event)).header("Authorization", "Bearer " + manager))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].userId").value(volunteer.toString()))
                    .andExpect(jsonPath("$.items[0].firstName").value("volunteer"))
                    .andExpect(jsonPath("$.items[0].email").doesNotExist());
        mvc.perform(get("/api/users/me/registrations").header("Authorization", "Bearer volunteer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Cleanup"))
                .andExpect(jsonPath("$.items[0].organizationId").value(org.toString()));
        mvc.perform(delete(path(event)).header("Authorization", "Bearer volunteer"))
                .andExpect(status().isNoContent());
        assertThat(rows.findById(id)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.events WHERE id=?",
                                Integer.class,
                                event))
                .isEqualTo(1);
        assertThat(service.status(headers("volunteer"), event).registered()).isFalse();
        mvc.perform(delete(path(event)).header("Authorization", "Bearer volunteer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
        assertThat(service.register(headers("volunteer"), event).id()).isNotEqualTo(id);
        var external = publish("owner", RegistrationType.EXTERNAL);
        mvc.perform(post(path(external)).header("Authorization", "Bearer volunteer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTERNAL_REGISTRATION_REQUIRED"));
        assertThat(service.status(headers("volunteer"), external).registered()).isFalse();
    }

    @Test
    void ownerAndAdminAlsoPersistAsVolunteersAcrossMultipleEvents() {
        UUID second = publish("owner", RegistrationType.INTERNAL);
        for (String token : List.of("owner", "admin", "volunteer")) {
            assertThat(service.register(headers(token), event).eventId()).isEqualTo(event);
            service.register(headers(token), second);
            assertThat(service.mine(headers(token), 0, 20).items()).hasSize(2);
        }
        assertThat(rows.count()).isEqualTo(6);
    }

    @Test
    void independentUsersCannotReadOrCancelAnotherUsersRowOrAccessOtherOrganizations()
            throws Exception {
        var registration = service.register(headers("volunteer"), event);
        assertThat(service.status(headers("other-volunteer"), event).registered()).isFalse();
        assertThat(service.mine(headers("other-volunteer"), 0, 20).items()).isEmpty();
        mvc.perform(
                        delete(path(event))
                                .header("Authorization", "Bearer other-volunteer")
                                .param("userId", volunteer.toString()))
                .andExpect(status().isNotFound());
        assertThat(rows.findById(registration.id())).isPresent();
        for (String token : List.of("volunteer", "other-owner"))
            mvc.perform(get(attendees(event)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        UUID otherEvent = publish("other-owner", RegistrationType.INTERNAL);
        for (String token : List.of("owner", "admin"))
            mvc.perform(get(attendees(otherEvent)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        mvc.perform(get(attendees(otherEvent)).header("Authorization", "Bearer other-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void pastEventsCannotChangeButStatusAndListsRetainHistory() throws Exception {
        service.register(headers("volunteer"), event);
        when(clock.instant())
                .thenReturn(
                        NOW.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        for (var request : List.of(post(path(event)), delete(path(event))))
            mvc.perform(request.header("Authorization", "Bearer volunteer"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EVENT_ALREADY_STARTED"));
        assertThat(service.status(headers("volunteer"), event).registered()).isTrue();
        assertThat(service.mine(headers("volunteer"), 0, 20).items()).hasSize(1);
        assertThat(service.attendees(headers("owner"), event, 0, 20).items()).hasSize(1);
        assertThat(rows.count()).isEqualTo(1);
    }

    List<Integer> race(Supplier<Integer> first, Supplier<Integer> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return first.get();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return second.get();
                            });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    int register(String token) {
        try {
            service.register(headers(token), event);
            return 201;
        } catch (RegistrationFailure e) {
            assertThat(e.code()).isEqualTo("ALREADY_REGISTERED");
            return e.status();
        }
    }

    int cancel() {
        try {
            service.cancel(headers("volunteer"), event);
            return 204;
        } catch (RegistrationFailure e) {
            assertThat(e.code()).isEqualTo("REGISTRATION_NOT_FOUND");
            return e.status();
        }
    }

    @Test
    void concurrentSameUserCreatesOneRowAndConcurrentCancellationDeletesOnce() throws Exception {
        assertThat(race(() -> register("volunteer"), () -> register("volunteer")))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(rows.count()).isEqualTo(1);
        assertThat(race(this::cancel, this::cancel)).containsExactlyInAnyOrder(204, 404);
        assertThat(rows.count()).isZero();
        assertThat(register("volunteer")).isEqualTo(201);
    }

    @Test
    void concurrentDifferentUsersBothRegister() throws Exception {
        assertThat(race(() -> register("volunteer"), () -> register("other-volunteer")))
                .containsExactly(201, 201);
        assertThat(rows.count()).isEqualTo(2);
    }

    @Test
    void timestampTiesHaveStableSqlPaginationAndNoCrossUserResults() {
        for (int i = 0; i < 5; i++)
            service.register(headers("volunteer"), publish("owner", RegistrationType.INTERNAL));
        service.register(headers("other-volunteer"), event);
        jdbc.update("UPDATE public.event_registrations SET registered_at=?", Timestamp.from(NOW));
        var expected =
                jdbc.queryForList(
                        "SELECT id FROM public.event_registrations WHERE user_id=? ORDER BY registered_at,id",
                        UUID.class,
                        volunteer);
        var actual = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            var page = service.mine(headers("volunteer"), i, 2);
            actual.addAll(page.items().stream().map(MyRegistrationResponse::id).toList());
            assertThat(page.hasNext()).isEqualTo(i < 2);
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(service.mine(headers("volunteer"), 3, 2).items()).isEmpty();
        service.register(headers("volunteer"), event);
        service.register(headers("owner"), event);
        service.register(headers("admin"), event);
        jdbc.update("UPDATE public.event_registrations SET registered_at=?", Timestamp.from(NOW));
        expected =
                jdbc.queryForList(
                        "SELECT id FROM public.event_registrations WHERE event_id=? ORDER BY registered_at,id",
                        UUID.class,
                        event);
        actual.clear();
        for (int i = 0; i < 2; i++) {
            var page = service.attendees(headers("owner"), event, i, 2);
            actual.addAll(page.items().stream().map(AttendeeResponse::id).toList());
            assertThat(page.hasNext()).isEqualTo(i == 0);
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void migrationEnforcesKeysDefaultsNullabilityAndNoActionForeignKeys() {
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO public.event_registrations(event_id,user_id) VALUES (?,?) RETURNING id",
                        UUID.class,
                        event,
                        volunteer);
        assertThat(id.version()).isEqualTo(4);
        assertThat(rows.findById(id).orElseThrow().response().registeredAt()).isNotNull();
        for (String sql :
                List.of(
                        "INSERT INTO public.event_registrations(event_id,user_id) SELECT event_id,user_id FROM public.event_registrations",
                        "INSERT INTO public.event_registrations(id,event_id,user_id) SELECT id,event_id,'"
                                + otherVolunteer
                                + "'::uuid FROM public.event_registrations",
                        "UPDATE public.event_registrations SET event_id='"
                                + UUID.randomUUID()
                                + "'",
                        "UPDATE public.event_registrations SET user_id='" + UUID.randomUUID() + "'",
                        "DELETE FROM public.users WHERE id='" + volunteer + "'",
                        "DELETE FROM public.events WHERE id='" + event + "'",
                        "UPDATE public.users SET id='"
                                + UUID.randomUUID()
                                + "' WHERE id='"
                                + volunteer
                                + "'",
                        "UPDATE public.events SET id='"
                                + UUID.randomUUID()
                                + "' WHERE id='"
                                + event
                                + "'"))
            assertThatThrownBy(() -> jdbc.execute(sql))
                    .isInstanceOf(DataIntegrityViolationException.class);
        for (String column : List.of("id", "event_id", "user_id", "registered_at"))
            assertThatThrownBy(
                            () ->
                                    jdbc.execute(
                                            "UPDATE public.event_registrations SET "
                                                    + column
                                                    + "=NULL"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid='public.event_registrations'::regclass AND contype='f' AND convalidated AND confdeltype='a' AND confupdtype='a'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(rows.count()).isEqualTo(1);
    }

    @Test
    void migrationIndexesRlsAndBrowserPrivilegesAreCorrect() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='event_registrations' ORDER BY ordinal_position",
                                String.class))
                .containsExactly("id", "event_id", "user_id", "registered_at");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='event_registrations' AND column_name='registered_at'",
                                String.class))
                .isEqualTo("timestamp with time zone");
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='event_registrations'",
                                String.class))
                .anyMatch(s -> s.contains("UNIQUE") && s.contains("(event_id, user_id)"))
                .anyMatch(s -> s.contains("(event_id, registered_at, id)"))
                .anyMatch(s -> s.contains("(user_id, registered_at, id)"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT relrowsecurity FROM pg_class WHERE oid='public.event_registrations'::regclass",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='event_registrations'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.table_privileges WHERE table_schema='public' AND table_name='event_registrations' AND grantee='PUBLIC'",
                                Integer.class))
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
                                        "SELECT has_table_privilege(?, 'public.event_registrations', ?)",
                                        Boolean.class,
                                        role,
                                        privilege))
                        .isFalse();
        // Effective browser restrictions, not just catalog inspection.
        for (String role : List.of("anon", "authenticated")) {
            assertThatThrownBy(
                            () -> {
                                try (var c = source.getConnection();
                                        var s = c.createStatement()) {
                                    s.execute("SET ROLE " + role);
                                    try {
                                        s.executeQuery("SELECT * FROM public.event_registrations");
                                    } finally {
                                        // SQL SET ROLE survives returning a connection to Hikari.
                                        s.execute("RESET ROLE");
                                    }
                                }
                            })
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void databaseFailureReturns503AndRollsBackWithoutLeakingSql() throws Exception {
        jdbc.execute(
                "CREATE FUNCTION public.reject_registration() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private database failure' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_registration AFTER INSERT ON public.event_registrations FOR EACH ROW EXECUTE FUNCTION public.reject_registration()");
        try {
            var result =
                    mvc.perform(post(path(event)).header("Authorization", "Bearer volunteer"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("REGISTRATION_UNAVAILABLE"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("private", "SQL", "23514");
            assertThat(rows.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_registration ON public.event_registrations");
            jdbc.execute("DROP FUNCTION public.reject_registration()");
        }
    }
}
