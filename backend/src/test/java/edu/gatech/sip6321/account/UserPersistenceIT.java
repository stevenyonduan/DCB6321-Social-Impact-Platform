package edu.gatech.sip6321.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Explicit opt-in: ./mvnw -Ppostgres-it verify. Requires Docker; never uses external credentials.
 */
@Testcontainers
@ActiveProfiles("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserPersistenceIT {
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
    @Autowired UserService service;
    @Autowired UserRepository users;
    private JdbcTemplate jdbc;
    private UUID id;
    private static final String EMAIL = "volunteer@example.test";

    @BeforeAll
    static void applyActualMigration(@Autowired DataSource dataSource) {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"))
                .execute(dataSource);
    }

    @BeforeEach
    void resetIsolatedDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM public.users");
        jdbc.update("DELETE FROM auth.users");
        id = UUID.randomUUID();
        authIdentity(id);
    }

    @Test
    void createsAndRetrievesAccountWithDatabaseTimestampAndNullProfile() {
        Instant before = Instant.now().minusSeconds(2);
        User created = service.createStandardAccount(id, EMAIL, "First", "Last");
        assertThat(created.getId()).isEqualTo(id);
        assertThat(created.isNew()).isFalse();
        assertThat(created.getCreatedAt()).isBetween(before, Instant.now().plusSeconds(2));
        User loaded = service.getById(id);
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getEmail()).isEqualTo(EMAIL);
        assertThat(loaded.getRole()).isEqualTo(UserRole.STANDARD);
        assertThat(loaded.getFirstName()).isEqualTo("First");
        assertThat(loaded.getLastName()).isEqualTo("Last");
        assertThat(loaded.getBio()).isNull();
        assertThat(loaded.getAvatarUrl()).isNull();
        assertThat(loaded.getOrganizationId()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(service.getByEmail(EMAIL).getId()).isEqualTo(id);
        assertThat(service.existsById(id)).isTrue();
        assertThat(service.existsByEmail(EMAIL)).isTrue();
        assertThat(service.existsById(UUID.randomUUID())).isFalse();
        assertThat(service.existsByEmail("absent@example.test")).isFalse();
    }

    @Test
    void mapsAllProfileColumnsAndTimestampOffset() {
        UUID organization = UUID.randomUUID();
        var timestamp = OffsetDateTime.parse("2026-10-30T09:00:00-04:00");
        jdbc.update(
                """
                INSERT INTO public.users
                  (id, email, role, first_name, last_name, bio, avatar_url, organization_id, created_at)
                VALUES (?, ?, 'STANDARD', 'First', 'Last', ?, ?, ?, ?)
                """,
                id,
                EMAIL,
                "Volunteer bio",
                "https://example.test/avatar.png",
                organization,
                timestamp);
        User loaded = service.getById(id);
        assertThat(loaded.getBio()).isEqualTo("Volunteer bio");
        assertThat(loaded.getAvatarUrl()).isEqualTo("https://example.test/avatar.png");
        assertThat(loaded.getOrganizationId()).isEqualTo(organization);
        assertThat(loaded.getCreatedAt()).isEqualTo(timestamp.toInstant());
    }

    @Test
    void missingAccountThrowsForBothLookups() {
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.getByEmail(EMAIL))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void duplicateServiceCallsPreserveOriginalAccount() {
        service.createStandardAccount(id, EMAIL, "First", "Last");
        assertThatThrownBy(
                        () ->
                                service.createStandardAccount(
                                        id, "other@example.test", "Changed", "Last"))
                .isInstanceOf(DuplicateAccountException.class);
        UUID other = UUID.randomUUID();
        authIdentity(other);
        assertThatThrownBy(() -> service.createStandardAccount(other, EMAIL, "Changed", "Last"))
                .isInstanceOf(DuplicateAccountException.class);
        assertThat(service.getById(id).getFirstName()).isEqualTo("First");
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void repositoryInsertWithAssignedDuplicateUuidNeverMerges() {
        service.createStandardAccount(id, EMAIL, "First", "Last");
        assertThatThrownBy(
                        () ->
                                users.saveAndFlush(
                                        User.standardAccount(
                                                id, "other@example.test", "Changed", "Last")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.getById(id).getEmail()).isEqualTo(EMAIL);
        assertThat(service.getById(id).getFirstName()).isEqualTo("First");
    }

    @Test
    void databaseRejectsDuplicateEmailIndependentlyOfServicePrechecks() {
        service.createStandardAccount(id, EMAIL, "First", "Last");
        UUID other = UUID.randomUUID();
        authIdentity(other);
        assertThatThrownBy(
                        () ->
                                users.saveAndFlush(
                                        User.standardAccount(other, EMAIL, "Other", "User")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsNonexistentAuthIdentity() {
        assertThatThrownBy(
                        () ->
                                service.createStandardAccount(
                                        UUID.randomUUID(), EMAIL, "First", "Last"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.count()).isZero();
    }

    @Test
    void databaseEnforcesRoleNullabilityLengthsAndAuthDeletionRestriction() {
        service.createStandardAccount(id, EMAIL, "First", "Last");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE public.users SET role = 'SUPERUSER' WHERE id = ?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String column : List.of("email", "role", "first_name", "last_name", "created_at")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE public.users SET "
                                                    + column
                                                    + " = NULL WHERE id = ?",
                                            id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String column : List.of("email", "first_name", "last_name", "avatar_url")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE public.users SET "
                                                    + column
                                                    + " = ? WHERE id = ?",
                                            "x".repeat(256),
                                            id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auth.users WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaHasExactlyNineColumnsNoUuidDefaultAndNoOrganizationForeignKey() {
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' ORDER BY ordinal_position
                """,
                                String.class))
                .containsExactly(
                        "id",
                        "email",
                        "role",
                        "first_name",
                        "last_name",
                        "bio",
                        "avatar_url",
                        "organization_id",
                        "created_at");
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'id'
                """,
                                String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'public.users'::regclass AND contype = 'f'
                """,
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void browserRolesHaveNoTablePrivilegesAndRlsIsEnabled() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT relrowsecurity FROM pg_class WHERE oid = 'public.users'::regclass",
                                Boolean.class))
                .isTrue();
        for (String role : List.of("anon", "authenticated")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT has_table_privilege(?, 'public.users', ?)",
                                        Boolean.class,
                                        role,
                                        privilege))
                        .isFalse();
            }
        }
    }

    private void authIdentity(UUID identity) {
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", identity);
    }
}
