package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.opportunity.OpportunityService;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
class OrganizationManagementPersistenceIT {
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
    @Autowired UserService accounts;
    @Autowired UserRepository users;
    @Autowired OrganizationManagementService management;
    @Autowired OrganizationService organizations;
    @Autowired OpportunityService opportunities;
    @Autowired ProfileService profiles;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean Clock clock;
    JdbcTemplate jdbc;
    UUID sarah, mike;
    static final List<String> SARAH = List.of("Bearer sarah"), MIKE = List.of("Bearer mike");
    static final String TEAM = "/api/organizations/me/admins";
    static final String EVENT =
            """
        {"title":"Build homes","description":"Help the community","eventStart":"2027-02-01T12:00:00Z","eventEnd":"2027-02-01T14:00:00Z","location":"Atlanta"}
        """;

    @BeforeAll
    static void migrate(@Autowired DataSource source) throws Exception {
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            for (String file :
                    List.of(
                            "V001__create_users.sql",
                            "V002__create_organizations.sql",
                            "V003__create_events.sql",
                            "V004__organization_management.sql"))
                s.execute(Files.readString(Path.of("db/migrations/" + file)));
        }
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM public.organization_management_audit");
        jdbc.update("DELETE FROM public.events");
        jdbc.update("DELETE FROM public.users");
        jdbc.update("DELETE FROM public.organizations");
        jdbc.update("DELETE FROM auth.users");
        sarah = account("sarah");
        mike = account("mike");
        when(clock.instant()).thenReturn(Instant.parse("2027-01-01T00:00:00Z"));
    }

    UUID account(String name) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", id);
        accounts.createStandardAccount(id, name + "@example.test", name, "Volunteer");
        when(auth.verifyAccessToken(name)).thenReturn(id);
        return id;
    }

    CreatedOrganization create(List<String> headers) {
        return management.create(headers, new CreateOrganizationRequest("Habitat", null, null));
    }

    @Test
    void fullSarahMikeScenarioWithImmediateGrantRevocationAndVolunteerPreservation()
            throws Exception {
        var initialMike =
                jdbc.queryForMap(
                        "SELECT id,email,role,first_name,last_name,created_at FROM public.users WHERE id=?",
                        mike);
        var result =
                mvc.perform(
                                post("/api/organizations")
                                        .header("Authorization", "Bearer sarah")
                                        .contentType("application/json")
                                        .content(
                                                "{\"name\":\"Habitat Community Volunteers\",\"website\":\"https://example.org\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.organizationRole").value("OWNER"))
                        .andExpect(jsonPath("$.organization.isVerified").value(false))
                        .andReturn();
        var org =
                UUID.fromString(
                        json.readTree(result.getResponse().getContentAsString())
                                .get("organization")
                                .get("id")
                                .asString());
        assertThat(profiles.me(SARAH).role()).isEqualTo(UserRole.STANDARD);
        assertThat(profiles.me(SARAH).organizationRole()).isEqualTo(OrganizationRole.OWNER);
        assertThatThrownBy(() -> create(SARAH)).isInstanceOf(OrganizationFailure.class);
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer sarah")
                                .contentType("application/json")
                                .content("{\"email\":\"mike@example.test\"}"))
                .andExpect(status().isNoContent());
        assertThat(profiles.me(MIKE).organizationRole()).isEqualTo(OrganizationRole.ADMIN);
        for (String token : List.of("sarah", "mike")) {
            mvc.perform(get("/api/organizations/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(org.toString()));
            mvc.perform(
                            patch("/api/organizations/" + org)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json")
                                    .content("{\"description\":\"Updated\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get(TEAM).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
            String body =
                    token.equals("sarah")
                            ? EVENT
                            : EVENT.replace(
                                    "\"location\":\"Atlanta\"",
                                    "\"location\":\"Atlanta\",\"registrationType\":\"EXTERNAL\",\"registrationUrl\":\"https://example.org/signup\"");
            mvc.perform(
                            post("/api/organizations/me/opportunities")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.organizationId").value(org.toString()))
                    .andExpect(
                            jsonPath("$.creatorId")
                                    .value((token.equals("sarah") ? sarah : mike).toString()));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.events WHERE organization_id=?",
                                Integer.class,
                                org))
                .isEqualTo(2);
        var other = account("other");
        var otherOrg = create(List.of("Bearer other")).organization().id();
        for (String token : List.of("sarah", "mike"))
            mvc.perform(
                            patch("/api/organizations/" + otherOrg)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/organizations/" + otherOrg + "/opportunities")
                                .header("Authorization", "Bearer mike"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer mike")
                                .contentType("application/json")
                                .content("{\"email\":\"other@example.test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(TEAM + "/" + sarah).header("Authorization", "Bearer sarah"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(TEAM + "/" + mike).header("Authorization", "Bearer other"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(TEAM + "/" + mike).header("Authorization", "Bearer sarah"))
                .andExpect(status().isNoContent());
        assertThat(profiles.me(MIKE).organizationId()).isNull();
        assertThat(profiles.me(MIKE).organizationRole()).isNull();
        mvc.perform(
                        patch("/api/organizations/" + org)
                                .header("Authorization", "Bearer mike")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/organizations/me/opportunities")
                                .header("Authorization", "Bearer mike")
                                .contentType("application/json")
                                .content(EVENT))
                .andExpect(status().isForbidden());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,email,role,first_name,last_name,created_at FROM public.users WHERE id=?",
                                mike))
                .isEqualTo(initialMike);
        assertThat(
                        jdbc.queryForList(
                                "SELECT action FROM public.organization_management_audit WHERE organization_id=? ORDER BY created_at,id",
                                String.class,
                                org))
                .containsExactly("OWNER_CREATED", "ADMIN_ADDED", "ADMIN_REMOVED");
        assertThat(users.findByOrganizationIdOrderById(org))
                .extracting(User::getId)
                .containsExactly(sarah);
        assertThat(other).isNotEqualTo(sarah);
    }

    @Test
    void simultaneousCreationClaimsExactlyOneOrganizationAndRollsBackLoser() throws Exception {
        var outcomes = race(() -> create(SARAH), () -> create(SARAH));
        assertThat(outcomes).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.organizations", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.users WHERE organization_role='OWNER'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.organization_management_audit",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void simultaneousDifferentOwnersCannotClaimSameVolunteer() throws Exception {
        var a = create(SARAH).organization().id();
        var b = create(MIKE).organization().id();
        var target = account("target");
        var outcomes =
                race(
                        () -> {
                            management.addAdmin(SARAH, new AddAdminRequest("target@example.test"));
                            return null;
                        },
                        () -> {
                            management.addAdmin(MIKE, new AddAdminRequest("target@example.test"));
                            return null;
                        });
        assertThat(outcomes).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(profiles.me(List.of("Bearer target")).organizationId()).isIn(a, b);
        assertThat(profiles.me(List.of("Bearer target")).organizationRole())
                .isEqualTo(OrganizationRole.ADMIN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.organization_management_audit WHERE target_id=? AND action='ADMIN_ADDED'",
                                Integer.class,
                                target))
                .isEqualTo(1);
    }

    List<Boolean> race(Callable<?> a, Callable<?> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (var task : List.of(a, b))
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        task.call();
                                        return true;
                                    } catch (OrganizationFailure e) {
                                        assertThat(e.status()).isEqualTo(409);
                                        return false;
                                    }
                                }));
            start.countDown();
            return List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS),
                    futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedOwnerAssignmentRollsBackNewOrganizationAndAudit() {
        jdbc.execute(
                "CREATE FUNCTION public.reject_owner() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.organization_role='OWNER' THEN RAISE EXCEPTION 'test' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_owner BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.reject_owner()");
        try {
            assertThatThrownBy(() -> create(SARAH))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.organizations", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.organization_management_audit",
                                    Integer.class))
                    .isZero();
            assertThat(profiles.me(SARAH).organizationId()).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER reject_owner ON public.users");
            jdbc.execute("DROP FUNCTION public.reject_owner()");
        }
    }

    @Test
    void failedAuditRollsBackMembershipChange() {
        create(SARAH);
        jdbc.execute(
                "CREATE FUNCTION public.reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_audit BEFORE INSERT ON public.organization_management_audit FOR EACH ROW EXECUTE FUNCTION public.reject_audit()");
        try {
            assertThatThrownBy(
                            () ->
                                    management.addAdmin(
                                            SARAH, new AddAdminRequest("mike@example.test")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(profiles.me(MIKE).organizationId()).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER reject_audit ON public.organization_management_audit");
            jdbc.execute("DROP FUNCTION public.reject_audit()");
        }
    }

    @Test
    void associationConstraintsUniqueOwnerMultipleAdminsAndBrowserProtections() {
        var org = create(SARAH).organization().id();
        var third = account("third");
        for (String sql :
                List.of(
                        "UPDATE public.users SET organization_role='INVALID' WHERE id=?",
                        "UPDATE public.users SET organization_role='ADMIN' WHERE id=?",
                        "UPDATE public.users SET organization_id='" + org + "' WHERE id=?",
                        "UPDATE public.users SET organization_id='"
                                + org
                                + "',organization_role='OWNER' WHERE id=?"))
            assertThatThrownBy(() -> jdbc.update(sql, mike))
                    .isInstanceOf(DataIntegrityViolationException.class);
        management.addAdmin(SARAH, new AddAdminRequest("mike@example.test"));
        management.addAdmin(SARAH, new AddAdminRequest("third@example.test"));
        assertThat(management.admins(SARAH)).hasSize(3);
        for (var table :
                List.of("users", "organizations", "events", "organization_management_audit")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT relrowsecurity FROM pg_class WHERE oid=?::regclass",
                                    Boolean.class,
                                    "public." + table))
                    .isTrue();
            for (var role : List.of("anon", "authenticated"))
                for (var privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE"))
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT has_table_privilege(?, ?, ?)",
                                            Boolean.class,
                                            role,
                                            "public." + table,
                                            privilege))
                            .isFalse();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE indexname='users_one_organization_owner_idx'",
                                String.class))
                .contains("UNIQUE", "WHERE", "OWNER");
        assertThat(profiles.me(List.of("Bearer third")).id()).isEqualTo(third);
    }

    @Test
    void missingSameAndOtherOrganizationAssignmentsAreSafeAndDoNotAlterAccounts() {
        var org = create(SARAH).organization().id();
        management.addAdmin(SARAH, new AddAdminRequest("mike@example.test"));
        var other = account("other");
        create(List.of("Bearer other"));
        var before = jdbc.queryForList("SELECT * FROM public.users ORDER BY id");
        for (String email :
                List.of(
                        "missing@example.test",
                        "sarah@example.test",
                        "mike@example.test",
                        "other@example.test")) {
            assertThatThrownBy(() -> management.addAdmin(SARAH, new AddAdminRequest(email)))
                    .isInstanceOf(OrganizationFailure.class)
                    .extracting("status")
                    .isEqualTo(409);
        }
        assertThat(jdbc.queryForList("SELECT * FROM public.users ORDER BY id")).isEqualTo(before);
        assertThat(users.getReferenceById(other)).isNotNull();
        assertThat(org).isNotNull();
    }

    @Test
    void legacyMigrationFailsClosedAndApprovedMappingPreservesGlobalRolesAndEvents()
            throws Exception {
        try (var c = legacyDatabase();
                var s = c.createStatement()) {
            UUID org = UUID.randomUUID(), owner = UUID.randomUUID(), admin = UUID.randomUUID();
            s.execute("INSERT INTO public.organizations(id,name) VALUES ('" + org + "','Legacy')");
            for (var user : List.of(owner, admin)) {
                s.execute("INSERT INTO auth.users VALUES ('" + user + "')");
                s.execute(
                        "INSERT INTO public.users(id,email,role,first_name,last_name,organization_id) VALUES ('"
                                + user
                                + "','"
                                + user
                                + "@example.test','ORGANIZATION','Legacy','User','"
                                + org
                                + "')");
            }
            s.execute(
                    "INSERT INTO public.events(organization_id,creator_id,title,description,event_start,event_end,location) VALUES ('"
                            + org
                            + "','"
                            + owner
                            + "','Legacy','Keep',NOW(),NOW()+interval '1 hour','Atlanta')");
            s.execute(Files.readString(Path.of("db/preflight/organization_management.sql")));
            var sql = Files.readString(Path.of("db/migrations/V004__organization_management.sql"));
            assertThatThrownBy(() -> s.execute(sql))
                    .hasMessageContaining("explicit OWNER/ADMIN reconciliation required");
            s.execute("ROLLBACK");
            try (var rows =
                    s.executeQuery(
                            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='organization_role'")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            String mappings =
                    "INSERT INTO approved_organization_roles VALUES ('"
                            + owner
                            + "','"
                            + org
                            + "','OWNER'),('"
                            + admin
                            + "','"
                            + org
                            + "','ADMIN');\n";
            s.execute(
                    sql.replace(
                            "-- INSERT INTO approved_organization_roles VALUES",
                            mappings + "-- INSERT INTO approved_organization_roles VALUES"));
            try (var rows =
                    s.executeQuery(
                            "SELECT count(*),bool_and(role='ORGANIZATION'),count(*) FILTER (WHERE organization_role='OWNER') FROM public.users")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getInt(3)).isEqualTo(1);
            }
            try (var rows =
                    s.executeQuery(
                            "SELECT count(*) FROM public.events WHERE title='Legacy' AND registration_type='INTERNAL'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void ownerlessLegacyOrganizationRequiresExplicitRegisteredOwnerAndDuplicateOwnersFail()
            throws Exception {
        try (var c = legacyDatabase();
                var s = c.createStatement()) {
            UUID org = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
            s.execute(
                    "INSERT INTO public.organizations(id,name) VALUES ('"
                            + org
                            + "','Empty legacy')");
            for (var id : List.of(owner, other)) {
                s.execute("INSERT INTO auth.users VALUES ('" + id + "')");
                s.execute(
                        "INSERT INTO public.users(id,email,role,first_name,last_name) VALUES ('"
                                + id
                                + "','"
                                + id
                                + "@example.test','STANDARD','Legacy','User')");
            }
            s.execute(Files.readString(Path.of("db/preflight/organization_management.sql")));
            var sql = Files.readString(Path.of("db/migrations/V004__organization_management.sql"));
            assertThatThrownBy(() -> s.execute(sql))
                    .hasMessageContaining("reconciliation required");
            s.execute("ROLLBACK");
            String duplicate =
                    "INSERT INTO approved_organization_roles VALUES ('"
                            + owner
                            + "','"
                            + org
                            + "','OWNER'),('"
                            + other
                            + "','"
                            + org
                            + "','OWNER');\n";
            assertThatThrownBy(
                            () ->
                                    s.execute(
                                            sql.replace(
                                                    "-- INSERT INTO approved_organization_roles VALUES",
                                                    duplicate
                                                            + "-- INSERT INTO approved_organization_roles VALUES")))
                    .hasMessageContaining("reconciliation required");
            s.execute("ROLLBACK");
            String approved =
                    "INSERT INTO approved_organization_roles VALUES ('"
                            + owner
                            + "','"
                            + org
                            + "','OWNER');\n";
            s.execute(
                    sql.replace(
                            "-- INSERT INTO approved_organization_roles VALUES",
                            approved + "-- INSERT INTO approved_organization_roles VALUES"));
            try (var rows =
                    s.executeQuery(
                            "SELECT organization_role,role FROM public.users WHERE id='"
                                    + owner
                                    + "'")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("OWNER");
                assertThat(rows.getString(2)).isEqualTo("STANDARD");
            }
        }
    }

    java.sql.Connection legacyDatabase() throws Exception {
        String name = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + name);
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/"
                        + name;
        var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var s = c.createStatement()) {
            s.execute("CREATE SCHEMA auth; CREATE TABLE auth.users(id UUID PRIMARY KEY)");
            for (var file :
                    List.of(
                            "V001__create_users.sql",
                            "V002__create_organizations.sql",
                            "V003__create_events.sql"))
                s.execute(Files.readString(Path.of("db/migrations/" + file)));
        }
        return c;
    }
}
