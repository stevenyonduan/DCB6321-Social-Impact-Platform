package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("database")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "SIP_AUTH_ENABLED=true")
class OrganizationPersistenceIT {
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

    @Autowired OrganizationProvisioningService provisioning;
    @Autowired OrganizationService service;
    @Autowired OrganizationRepository organizations;
    @Autowired UserService users;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoBean SupabaseAuthClient auth;
    @Autowired MockMvc mvc;
    private JdbcTemplate jdbc;
    private UUID manager;
    private UUID organizationId;
    private static final List<String> AUTH = List.of("Bearer isolated-access");

    @BeforeAll
    static void migrate(@Autowired DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"))
                .execute(dataSource);
        // Prove that applying V002 preserves an existing STANDARD account, not just an empty table.
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", legacy);
        jdbc.update(
                "INSERT INTO public.users(id,email,role,first_name,last_name) VALUES (?, 'legacy@example.test','STANDARD','Legacy','User')",
                legacy);
        var before = jdbc.queryForList("SELECT * FROM public.users");
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"))
                .execute(dataSource);
        assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(before);
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM public.users");
        jdbc.update("DELETE FROM public.organizations");
        jdbc.update("DELETE FROM auth.users");
        organizationId =
                provisioning.create("Original", "Description", "https://example.test").id();
        manager = account("ORGANIZATION", organizationId);
        when(auth.verifyAccessToken("isolated-access")).thenReturn(manager);
    }

    private UUID account(String role, UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users VALUES (?)", id);
        users.createStandardAccount(id, id + "@example.test", "First", "Last");
        jdbc.update(
                "UPDATE public.users SET role=?, organization_id=? WHERE id=?",
                role,
                organization,
                id);
        return id;
    }

    @Test
    void concurrentCreationAssignsExactlyOneOrganizationAndEnablesEditing() throws Exception {
        UUID creator = account("STANDARD", null);
        when(auth.verifyAccessToken("isolated-access")).thenReturn(creator);
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> create =
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return mvc.perform(
                                        post("/api/organizations")
                                                .header("Authorization", "Bearer isolated-access")
                                                .contentType("application/json")
                                                .content("{\"name\":\"Garden\"}"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
        }
        var owner = users.getById(creator);
        assertThat(owner.getRole()).isEqualTo(UserRole.ORGANIZATION);
        assertThat(owner.getOrganizationId()).isNotNull();
        assertThat(organizations.count()).isEqualTo(2);
        assertThat(service.me(AUTH).id()).isEqualTo(owner.getOrganizationId());
        assertThat(
                        service.patch(
                                        AUTH,
                                        owner.getOrganizationId(),
                                        OrganizationPatch.fromJson(
                                                json.readTree("{\"name\":\"Updated\"}")))
                                .name())
                .isEqualTo("Updated");
    }

    @Test
    void failedOwnershipAssignmentRollsBackTheOrganizationInsert() {
        UUID creator = account("STANDARD", null);
        when(auth.verifyAccessToken("isolated-access")).thenReturn(creator);
        jdbc.execute(
                "ALTER TABLE public.users ADD CONSTRAINT test_reject_assignment CHECK (role <> 'ORGANIZATION' OR id <> '"
                        + creator
                        + "'::uuid)");
        try {
            assertThatThrownBy(
                            () ->
                                    service.create(
                                            AUTH,
                                            OrganizationPatch.fromJson(
                                                    json.readTree("{\"name\":\"Garden\"}"))))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(organizations.count()).isEqualTo(1);
            var account = users.getById(creator);
            assertThat(account.getRole()).isEqualTo(UserRole.STANDARD);
            assertThat(account.getOrganizationId()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE public.users DROP CONSTRAINT test_reject_assignment");
        }
    }

    @Test
    void migrationWithDanglingLegacyAssociationRollsBackWithoutChangingAccount() throws Exception {
        // Separate disposable database: never alter the successfully migrated application fixture.
        jdbc.execute("CREATE DATABASE organization_migration_failure");
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/organization_migration_failure";
        try (var connection =
                        DriverManager.getConnection(
                                url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA auth; CREATE TABLE auth.users(id UUID PRIMARY KEY)");
            statement.execute(Files.readString(Path.of("db/migrations/V001__create_users.sql")));
            UUID legacy = UUID.randomUUID();
            statement.execute("INSERT INTO auth.users VALUES ('" + legacy + "')");
            statement.execute(
                    "INSERT INTO public.users(id,email,role,first_name,last_name,organization_id) VALUES ('"
                            + legacy
                            + "','legacy@example.test','ORGANIZATION','Legacy','Manager','"
                            + UUID.randomUUID()
                            + "')");
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            Files.readString(
                                                    Path.of(
                                                            "db/migrations/V002__create_organizations.sql"))))
                    .isInstanceOf(SQLException.class);
            statement.execute("ROLLBACK");
            try (var result =
                    statement.executeQuery(
                            "SELECT to_regclass('public.organizations') IS NULL, count(*) = 1, bool_and(organization_id IS NOT NULL) FROM public.users")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
                assertThat(result.getBoolean(2)).isTrue();
                assertThat(result.getBoolean(3)).isTrue();
            }
        }
    }

    @Test
    void endpointEditsPersistAndCrossOrganizationEndpointEditsDoNot() throws Exception {
        String path = "/api/organizations/" + organizationId;
        mvc.perform(
                        patch(path)
                                .header("Authorization", "Bearer isolated-access")
                                .contentType("application/json")
                                .content("{\"name\":\"HTTP persisted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HTTP persisted"));
        assertThat(organizations.findById(organizationId).orElseThrow().getName())
                .isEqualTo("HTTP persisted");
        mvc.perform(get(path).header("Authorization", "Bearer isolated-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HTTP persisted"));
        UUID otherOrg = provisioning.create("Other", null, null).id();
        UUID otherManager = account("ORGANIZATION", otherOrg);
        when(auth.verifyAccessToken("other-access")).thenReturn(otherManager);
        var before = jdbc.queryForList("SELECT * FROM public.organizations ORDER BY id");
        mvc.perform(
                        patch(path)
                                .header("Authorization", "Bearer other-access")
                                .contentType("application/json")
                                .content("{\"name\":\"Denied HTTP\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch(path)
                                .header("Authorization", "Bearer isolated-access")
                                .contentType("application/json")
                                .content("{\"isVerified\":true}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT * FROM public.organizations ORDER BY id"))
                .isEqualTo(before);
    }

    @Test
    void createsWithGeneratedUuidDatabaseTimestampDefaultVerificationAndNullableFields() {
        var created = provisioning.create("Nullable", null, null);
        assertThat(created.id()).isNotNull().isNotEqualTo(organizationId);
        assertThat(created.id().version()).isEqualTo(4);
        assertThat(created.createdAt())
                .isBetween(Instant.now().minusSeconds(10), Instant.now().plusSeconds(2));
        assertThat(created.isVerified()).isFalse();
        var loaded = organizations.findById(created.id()).orElseThrow();
        assertThat(loaded.getDescription()).isNull();
        assertThat(loaded.getWebsite()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(created.createdAt());
        UUID sqlId =
                jdbc.queryForObject(
                        "INSERT INTO public.organizations(name) VALUES ('SQL default') RETURNING id",
                        UUID.class);
        assertThat(sqlId).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_verified FROM public.organizations WHERE id=?",
                                Boolean.class,
                                sqlId))
                .isFalse();
    }

    @Test
    void readsAndPersistsAuthorizedPatchAfterReloadWithoutChangingProtectedFields() {
        var before = service.me(AUTH);
        assertThat(service.get(AUTH, organizationId)).isEqualTo(before);
        var patch =
                OrganizationPatch.fromJson(
                        json.readTree("{\"name\":\"Updated\",\"website\":null}"));
        var changed = service.patch(AUTH, organizationId, patch);
        var reloaded = organizations.findById(organizationId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Updated");
        assertThat(reloaded.getDescription()).isEqualTo("Description");
        assertThat(reloaded.getWebsite()).isNull();
        assertThat(changed.id()).isEqualTo(before.id());
        assertThat(changed.createdAt()).isEqualTo(before.createdAt());
        assertThat(changed.isVerified()).isFalse();
        service.patch(
                AUTH,
                organizationId,
                OrganizationPatch.fromJson(json.readTree("{\"description\":null}")));
        assertThat(organizations.findById(organizationId).orElseThrow().getDescription()).isNull();
    }

    @Test
    void otherOrganizationStandardAdminAndUnassociatedAccountsCannotModifyAnyRows() {
        UUID other = provisioning.create("Other", null, null).id();
        for (UUID identity :
                List.of(
                        account("ORGANIZATION", other),
                        account("STANDARD", organizationId),
                        account("ADMIN", organizationId),
                        account("ORGANIZATION", null))) {
            when(auth.verifyAccessToken("isolated-access")).thenReturn(identity);
            var before = jdbc.queryForList("SELECT * FROM public.organizations ORDER BY id");
            assertThatThrownBy(
                            () ->
                                    service.patch(
                                            AUTH,
                                            organizationId,
                                            OrganizationPatch.fromJson(
                                                    json.readTree("{\"name\":\"Denied\"}"))))
                    .isInstanceOf(OrganizationFailure.class)
                    .extracting("status")
                    .isEqualTo(403);
            assertThat(jdbc.queryForList("SELECT * FROM public.organizations ORDER BY id"))
                    .isEqualTo(before);
        }
    }

    @Test
    void standardAccountsRemainValidAndInvalidAssociationsAndDeletesAreRejected() {
        UUID standard = account("STANDARD", null);
        assertThat(users.getById(standard).getOrganizationId()).isNull();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE public.users SET organization_id=? WHERE id=?",
                                        UUID.randomUUID(),
                                        standard))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM public.organizations WHERE id=?",
                                        organizationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.getById(manager).getOrganizationId()).isEqualTo(organizationId);
    }

    @Test
    void invalidAndProtectedPatchAttemptsLeaveDatabaseUnchanged() {
        var before = jdbc.queryForList("SELECT * FROM public.organizations");
        for (String body :
                List.of(
                        "{\"isVerified\":true}",
                        "{\"id\":null}",
                        "{\"createdAt\":null}",
                        "{\"name\":null}",
                        "{\"website\":\"javascript:alert(1)\"}",
                        "{\"role\":\"ADMIN\"}")) {
            assertThatThrownBy(
                            () ->
                                    service.patch(
                                            AUTH,
                                            organizationId,
                                            OrganizationPatch.fromJson(json.readTree(body))))
                    .isInstanceOf(OrganizationFailure.class);
            assertThat(jdbc.queryForList("SELECT * FROM public.organizations")).isEqualTo(before);
        }
    }

    @Test
    void failedFlushRollsBackChangesAndDoesNotReturnSuccess() {
        jdbc.execute(
                "CREATE FUNCTION public.reject_org_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated update failure' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_org_update BEFORE UPDATE ON public.organizations FOR EACH ROW EXECUTE FUNCTION public.reject_org_update()");
        try {
            assertThatThrownBy(
                            () ->
                                    service.patch(
                                            AUTH,
                                            organizationId,
                                            OrganizationPatch.fromJson(
                                                    json.readTree("{\"name\":\"Rolled back\"}"))))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(organizations.findById(organizationId).orElseThrow().getName())
                    .isEqualTo("Original");
        } finally {
            jdbc.execute("DROP TRIGGER reject_org_update ON public.organizations");
            jdbc.execute("DROP FUNCTION public.reject_org_update()");
        }
    }

    @Test
    void schemaAndRlsAndBrowserPrivilegesMatchMigration() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='organizations' ORDER BY ordinal_position",
                                String.class))
                .containsExactly(
                        "id", "name", "description", "website", "is_verified", "created_at");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid='public.users'::regclass AND confrelid='public.organizations'::regclass AND contype='f' AND convalidated",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT relrowsecurity FROM pg_class WHERE oid='public.organizations'::regclass",
                                Boolean.class))
                .isTrue();
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
                                        "SELECT has_table_privilege(?, 'public.organizations', ?)",
                                        Boolean.class,
                                        role,
                                        privilege))
                        .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename='organizations'",
                                Integer.class))
                .isZero();
    }
}
