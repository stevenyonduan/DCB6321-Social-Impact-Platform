package edu.gatech.sip6321.opportunity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import edu.gatech.sip6321.organization.OrganizationProvisioningService;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
class OpportunityPersistenceIT {
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

    @Autowired DataSource dataSource;
    @Autowired OrganizationProvisioningService provisioning;
    @Autowired UserService users;
    @Autowired EventRepository events;
    @Autowired OpportunityService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean Clock clock;
    static final Instant NOW = OpportunityServiceTest.NOW;
    JdbcTemplate jdbc;
    UUID orgA, orgB, managerA, managerB, standard;
    static final String POST = "/api/organizations/me/opportunities";
    static final String BODY = OpportunityControllerTest.BODY;

    @BeforeAll
    static void migrate(@Autowired DataSource source) {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"),
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"))
                .execute(source);
        var jdbc = new JdbcTemplate(source);
        var org = UUID.randomUUID();
        var user = UUID.randomUUID();
        jdbc.update("INSERT INTO public.organizations(id,name) VALUES (?, 'Legacy')", org);
        jdbc.update("INSERT INTO auth.users VALUES (?)", user);
        jdbc.update(
                "INSERT INTO public.users(id,email,role,first_name,last_name,organization_id) VALUES (?,'legacy@example.test','ORGANIZATION','Legacy','Manager',?)",
                user,
                org);
        var beforeUsers = jdbc.queryForList("SELECT * FROM public.users");
        var beforeOrgs = jdbc.queryForList("SELECT * FROM public.organizations");
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V003__create_events.sql"))
                .execute(source);
        assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(beforeUsers);
        assertThat(jdbc.queryForList("SELECT * FROM public.organizations")).isEqualTo(beforeOrgs);
    }

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM public.events");
        jdbc.update("DELETE FROM public.users");
        jdbc.update("DELETE FROM public.organizations");
        jdbc.update("DELETE FROM auth.users");
        orgA = provisioning.create("A", null, null).id();
        orgB = provisioning.create("B", null, null).id();
        managerA = account("ORGANIZATION", orgA);
        managerB = account("ORGANIZATION", orgB);
        standard = account("STANDARD", null);
        when(auth.verifyAccessToken("manager-a")).thenReturn(managerA);
        when(auth.verifyAccessToken("manager-b")).thenReturn(managerB);
        when(auth.verifyAccessToken("standard")).thenReturn(standard);
    }

    UUID account(String role, UUID org) {
        var user = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", user);
        users.createStandardAccount(user, user + "@example.test", "First", "Last");
        jdbc.update(
                "UPDATE public.users SET role=?, organization_id=? WHERE id=?", role, org, user);
        return user;
    }

    String path(UUID org) {
        return "/api/organizations/" + org + "/opportunities";
    }

    UUID publish(String token, String body) throws Exception {
        var result =
                mvc.perform(
                                post(POST)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        var response = json.readTree(result.getResponse().getContentAsString());
        var request = json.readTree(body);
        assertThat(response.get("registrationType").asString())
                .isEqualTo(
                        request.has("registrationType")
                                ? request.get("registrationType").asString()
                                : "INTERNAL");
        if (request.get("registrationUrl") == null || request.get("registrationUrl").isNull())
            assertThat(response.get("registrationUrl").isNull()).isTrue();
        else
            assertThat(response.get("registrationUrl").asString())
                    .isEqualTo(request.get("registrationUrl").asString());
        return UUID.fromString(response.get("id").asString());
    }

    @Test
    void publishesPersistsReloadsAndListsOnlyInOwningOrganization() throws Exception {
        UUID id = publish("manager-a", BODY);
        String url = "https://example.org/volunteer/register?event=" + "x".repeat(300);
        UUID externalId = publish("manager-a", externalBody(url));
        var row = jdbc.queryForMap("SELECT * FROM public.events WHERE id=?", id);
        assertThat(row.get("organization_id")).isEqualTo(orgA);
        assertThat(row.get("creator_id")).isEqualTo(managerA);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("registration_type")).isEqualTo("INTERNAL");
        assertThat(row.get("registration_url")).isNull();
        var external = events.findById(externalId).orElseThrow().response();
        assertThat(external.registrationType()).isEqualTo(RegistrationType.EXTERNAL);
        assertThat(external.registrationUrl()).isEqualTo(url);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT registration_url FROM public.events WHERE id=?",
                                String.class,
                                externalId))
                .isEqualTo(url);
        var reloaded = events.findById(id).orElseThrow().response();
        assertThat(reloaded.eventStart()).isEqualTo(Instant.parse("2027-01-16T14:00:00Z"));
        assertThat(reloaded.createdAt()).isNotNull();
        for (String token : List.of("manager-a", "manager-b", "standard")) {
            mvc.perform(get(path(orgA)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.items[0].creatorId").value(managerA.toString()))
                    .andExpect(jsonPath("$.items[0].registrationType").value("INTERNAL"))
                    .andExpect(jsonPath("$.items[0].registrationUrl").value((Object) null))
                    .andExpect(jsonPath("$.items[1].id").value(externalId.toString()))
                    .andExpect(jsonPath("$.items[1].registrationType").value("EXTERNAL"))
                    .andExpect(jsonPath("$.items[1].registrationUrl").value(url));
            mvc.perform(get(path(orgB)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }
        UUID bId = publish("manager-b", BODY);
        assertThat(events.findById(bId).orElseThrow().response().organizationId()).isEqualTo(orgB);
        var before = jdbc.queryForList("SELECT * FROM public.events ORDER BY id");
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer manager-a")
                                .contentType("application/json")
                                .content(BODY.replace("2027-01-16", "2000-01-16")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_START_NOT_FUTURE"));
        assertThat(jdbc.queryForList("SELECT * FROM public.events ORDER BY id")).isEqualTo(before);
        mvc.perform(
                        post(POST)
                                .header("Authorization", "Bearer standard")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForList("SELECT * FROM public.events ORDER BY id")).isEqualTo(before);
    }

    @Test
    void rejectsAdminAndUnassociatedManagersWithoutWrites() throws Exception {
        for (String role : List.of("ADMIN", "ORGANIZATION")) {
            UUID id = account(role, role.equals("ADMIN") ? orgA : null);
            when(auth.verifyAccessToken("denied")).thenReturn(id);
            mvc.perform(
                            post(POST)
                                    .header("Authorization", "Bearer denied")
                                    .contentType("application/json")
                                    .content(BODY))
                    .andExpect(status().isForbidden());
        }
        assertThat(events.count()).isZero();
    }

    @Test
    void invalidRequestsAndIdentityInjectionLeaveDatabaseUnchanged() throws Exception {
        for (String body :
                List.of(
                        BODY.replace("Cleanup", " "),
                        BODY.replace("2027-01-16T12:00:00-05:00", "2027-01-16T09:00:00-05:00"),
                        BODY.replace("\"imageUrl\":null", "\"organizationId\":\"" + orgB + "\""),
                        BODY.replace("\"imageUrl\":null", "\"creatorId\":\"" + managerB + "\""))) {
            mvc.perform(
                            post(POST)
                                    .header("Authorization", "Bearer manager-a")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
            assertThat(events.count()).isZero();
        }
    }

    @Test
    void upcomingSqlPaginationHasStableTieBreakerAndPreservesHistoricalEvents() throws Exception {
        for (int i = 0; i < 5; i++) publish("manager-a", BODY);
        publish("manager-a", BODY.replace("2027-01-16", "2027-01-17"));
        var pastId = storedEvent(Instant.parse("2000-01-01T00:00:00Z"), orgA, managerA);
        var atCutoffId = storedEvent(NOW.truncatedTo(ChronoUnit.MICROS), orgA, managerA);
        publish("manager-b", BODY);
        var expected =
                jdbc.queryForList(
                        "SELECT id FROM public.events WHERE organization_id=? AND event_start > ? ORDER BY event_start,id",
                        UUID.class,
                        orgA,
                        Timestamp.from(NOW.truncatedTo(ChronoUnit.MICROS)));
        var actual = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) {
            var result =
                    mvc.perform(
                                    get(path(orgA))
                                            .header("Authorization", "Bearer standard")
                                            .param("page", "" + page)
                                            .param("size", "2"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.items.length()").value(2))
                            .andExpect(jsonPath("$.hasNext").value(page < 2))
                            .andReturn();
            for (var item : json.readTree(result.getResponse().getContentAsString()).get("items"))
                actual.add(UUID.fromString(item.get("id").asString()));
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(actual).doesNotContain(pastId, atCutoffId);
        assertThat(events.findById(pastId)).isPresent();
        assertThat(events.findById(atCutoffId)).isPresent();
        assertThat(events.count()).isEqualTo(9);
        mvc.perform(
                        get(path(orgA))
                                .header("Authorization", "Bearer standard")
                                .param("page", "3")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void failedInsertRollsBackAndReturnsSanitizedFailure() throws Exception {
        jdbc.execute(
                "CREATE FUNCTION public.reject_event_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'secret event failure' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_event_insert AFTER INSERT ON public.events FOR EACH ROW EXECUTE FUNCTION public.reject_event_insert()");
        try {
            mvc.perform(
                            post(POST)
                                    .header("Authorization", "Bearer manager-a")
                                    .contentType("application/json")
                                    .content(BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("OPPORTUNITY_UNAVAILABLE"));
            assertThat(events.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_event_insert ON public.events");
            jdbc.execute("DROP FUNCTION public.reject_event_insert()");
        }
    }

    @Test
    void sqlDefaultsAndForeignKeysAreEnforcedWithoutCascades() {
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO public.events(organization_id,creator_id,title,description,event_start,event_end,location) VALUES (?,?,'SQL title','Description',NOW(),NOW()+interval '1 hour','Atlanta') RETURNING id",
                        UUID.class,
                        orgA,
                        managerA);
        assertThat(id).isNotNull();
        assertThat(id.version()).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_at BETWEEN NOW()-interval '1 minute' AND NOW() FROM public.events WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        for (String query :
                List.of(
                        "UPDATE public.events SET organization_id='" + UUID.randomUUID() + "'",
                        "UPDATE public.events SET creator_id='" + UUID.randomUUID() + "'",
                        "DELETE FROM public.users WHERE id='" + managerA + "'",
                        "UPDATE public.users SET id='"
                                + UUID.randomUUID()
                                + "' WHERE id='"
                                + managerA
                                + "'",
                        "DELETE FROM public.organizations WHERE id='" + orgA + "'",
                        "UPDATE public.organizations SET id='"
                                + UUID.randomUUID()
                                + "' WHERE id='"
                                + orgA
                                + "'"))
            assertThatThrownBy(() -> jdbc.execute(query))
                    .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void requiredColumnsAndVarcharConstraintsMatchSchema() {
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO public.events(organization_id,creator_id,title,description,event_start,event_end,location) VALUES (?,?,'Title','Description',NOW(),NOW()+interval '1 hour','Atlanta') RETURNING id",
                        UUID.class,
                        orgA,
                        managerA);
        for (String column :
                List.of(
                        "id",
                        "organization_id",
                        "creator_id",
                        "title",
                        "description",
                        "event_start",
                        "event_end",
                        "location",
                        "created_at",
                        "registration_type"))
            assertThatThrownBy(() -> jdbc.execute("UPDATE public.events SET " + column + "=NULL"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        for (String column : List.of("title", "location", "image_url")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE public.events SET " + column + "=?",
                                            "😀".repeat(256)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE public.events SET " + column + "=?", "😀".repeat(255));
        }
        jdbc.update("UPDATE public.events SET description=?", "x".repeat(50000));
        assertThat(events.findById(id).orElseThrow().response().description()).hasSize(50000);
    }

    @Test
    void schemaRlsAndPrivilegesMatchMigration() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='events' ORDER BY ordinal_position",
                                String.class))
                .containsExactly(
                        "id",
                        "organization_id",
                        "creator_id",
                        "title",
                        "description",
                        "image_url",
                        "event_start",
                        "event_end",
                        "location",
                        "created_at",
                        "registration_type",
                        "registration_url");
        assertThat(
                        jdbc.queryForList(
                                "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='events' AND column_name IN ('event_start','event_end','created_at')",
                                String.class))
                .containsOnly("timestamp with time zone")
                .hasSize(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid='public.events'::regclass AND contype='f' AND convalidated AND confdeltype='a' AND confupdtype='a'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT relrowsecurity FROM pg_class WHERE oid='public.events'::regclass",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='events'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.table_privileges WHERE table_schema='public' AND table_name='events' AND grantee='PUBLIC'",
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
                                        "SELECT has_table_privilege(?, 'public.events', ?)",
                                        Boolean.class,
                                        role,
                                        privilege))
                        .isFalse();
    }

    private String externalBody(String url) {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put("registrationType", "EXTERNAL");
        node.put("registrationUrl", url);
        node.put("eventStart", "2027-01-17T09:00:00-05:00");
        node.put("eventEnd", "2027-01-17T12:00:00-05:00");
        return json.writeValueAsString(node);
    }

    private UUID storedEvent(Instant start, UUID organization, UUID creator) {
        return jdbc.queryForObject(
                "INSERT INTO public.events(organization_id,creator_id,title,description,event_start,event_end,location) VALUES (?,?,'Fixture','Description',?,?,'Atlanta') RETURNING id",
                UUID.class,
                organization,
                creator,
                Timestamp.from(start),
                Timestamp.from(start.plusSeconds(3600)));
    }

    @Test
    void registrationDefaultsAndCombinationsAreEnforcedByPostgres() {
        UUID id = storedEvent(NOW.plusSeconds(3600), orgA, managerA);
        var internal = events.findById(id).orElseThrow().response();
        assertThat(internal.registrationType()).isEqualTo(RegistrationType.INTERNAL);
        assertThat(internal.registrationUrl()).isNull();
        for (String[] invalid :
                new String[][] {
                    {null, null},
                    {"UNKNOWN", null},
                    {"internal", null},
                    {"INTERNAL", "https://example.org"},
                    {"INTERNAL", ""},
                    {"EXTERNAL", null},
                    {"EXTERNAL", ""},
                    {"EXTERNAL", " "},
                    {"EXTERNAL", "\t\n\r"}
                }) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE public.events SET registration_type=?, registration_url=? WHERE id=?",
                                            invalid[0],
                                            invalid[1],
                                            id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update(
                "UPDATE public.events SET registration_type='EXTERNAL', registration_url=? WHERE id=?",
                "https://example.org/register",
                id);
        assertThat(events.findById(id).orElseThrow().response().registrationType())
                .isEqualTo(RegistrationType.EXTERNAL);
        jdbc.update(
                "UPDATE public.events SET registration_type='INTERNAL', registration_url=NULL WHERE id=?",
                id);
        assertThat(events.findById(id).orElseThrow().response().registrationType())
                .isEqualTo(RegistrationType.INTERNAL);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid='public.events'::regclass AND conname IN ('events_registration_type_check','events_registration_url_check') AND convalidated",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='events' AND column_name='registration_type'",
                                String.class))
                .contains("INTERNAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='events' AND column_name='registration_url'",
                                String.class))
                .isEqualTo("text");
    }

    @Test
    void publicationRejectsPastEqualAndSubMicrosecondFutureStartsWithoutRows() throws Exception {
        for (Instant start : List.of(NOW.minusSeconds(1), NOW, NOW.plusNanos(1))) {
            var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
            node.put("eventStart", start.toString());
            mvc.perform(
                            post(POST)
                                    .header("Authorization", "Bearer manager-a")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(node)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EVENT_START_NOT_FUTURE"));
            assertThat(events.count()).isZero();
        }
        var nextMicro = Instant.parse("2027-01-01T00:00:00.123457Z");
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(BODY);
        node.put("eventStart", nextMicro.toString());
        UUID id = publish("manager-a", json.writeValueAsString(node));
        assertThat(events.findById(id).orElseThrow().response().eventStart()).isEqualTo(nextMicro);
        mvc.perform(get(path(orgA)).header("Authorization", "Bearer standard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()));
        when(clock.instant()).thenReturn(nextMicro);
        mvc.perform(get(path(orgA)).header("Authorization", "Bearer standard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertThat(events.findById(id)).isPresent();
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void agingEventsDisappearFromListingsButRemainStored() throws Exception {
        UUID id = publish("manager-a", BODY);
        when(clock.instant()).thenReturn(Instant.parse("2027-01-16T14:00:00Z"));
        mvc.perform(get(path(orgA)).header("Authorization", "Bearer standard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        when(clock.instant()).thenReturn(Instant.parse("2028-01-01T00:00:00Z"));
        mvc.perform(get(path(orgA)).header("Authorization", "Bearer standard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertThat(events.findById(id)).isPresent();
    }

    @Test
    void invalidRegistrationNeverWritesAndExplicitInternalPersists() throws Exception {
        for (String fields :
                List.of(
                        "\"registrationType\":null",
                        "\"registrationType\":\"OTHER\"",
                        "\"registrationType\":\"EXTERNAL\"",
                        "\"registrationType\":\"INTERNAL\",\"registrationUrl\":\"https://example.org\"",
                        "\"registrationType\":\"EXTERNAL\",\"registrationUrl\":\"javascript:alert(1)\"")) {
            String body = BODY.replace("\"imageUrl\":null", fields);
            mvc.perform(
                            post(POST)
                                    .header("Authorization", "Bearer manager-a")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
            assertThat(events.count()).isZero();
        }
        UUID id =
                publish(
                        "manager-a",
                        BODY.replace(
                                "\"imageUrl\":null",
                                "\"registrationType\":\"INTERNAL\",\"registrationUrl\":null"));
        assertThat(events.findById(id).orElseThrow().response().registrationType())
                .isEqualTo(RegistrationType.INTERNAL);
        assertThat(events.findById(id).orElseThrow().response().registrationUrl()).isNull();
    }
}
