package edu.gatech.sip6321.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.auth.LoginFailure;
import edu.gatech.sip6321.auth.LoginRequest;
import edu.gatech.sip6321.auth.LoginService;
import edu.gatech.sip6321.auth.SignupFailure;
import edu.gatech.sip6321.auth.SignupRequest;
import edu.gatech.sip6321.auth.SignupService;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import edu.gatech.sip6321.diagnostics.DatabaseDiagnostics;
import edu.gatech.sip6321.diagnostics.DiagnosticHikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Explicit opt-in: ./mvnw -Ppostgres-it verify. Requires Docker; never uses external credentials.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
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
    void loginReadsExistingAccountWithoutChangingAnyColumnsAndNeverProvisionsMissingAccount() {
        service.createStandardAccount(id, EMAIL, "First", "Last");
        var before = jdbc.queryForList("SELECT * FROM public.users");
        SupabaseAuthClient auth = mock(SupabaseAuthClient.class);
        var request = new LoginRequest(EMAIL, "password-never-log");
        var session =
                new SupabaseAuthClient.Session(
                        "access-never-log",
                        "refresh-never-log",
                        "bearer",
                        3600,
                        Instant.now().getEpochSecond() + 3600);
        when(auth.login(request))
                .thenReturn(new SupabaseAuthClient.VerifiedLogin(id, EMAIL, session));
        var login = new LoginService(auth, service);
        assertThat(login.login(request).user().id()).isEqualTo(id);
        assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(before);
        jdbc.update("DELETE FROM public.users");
        assertThatThrownBy(() -> login.login(request))
                .isInstanceOf(LoginFailure.class)
                .extracting("code")
                .isEqualTo("ACCOUNT_PROVISIONING_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users", Long.class)).isZero();
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

    private SupabaseAuthClient signupAuth() {
        SupabaseAuthClient auth = mock(SupabaseAuthClient.class);
        when(auth.signup(any()))
                .thenReturn(
                        new SupabaseAuthClient.VerifiedSignup(
                                id,
                                EMAIL,
                                new SupabaseAuthClient.Session(
                                        "isolated-access",
                                        "isolated-refresh",
                                        "bearer",
                                        3600,
                                        Instant.now().getEpochSecond() + 3600)));
        return auth;
    }

    private SignupRequest signupRequest() {
        return new SignupRequest(EMAIL, "not-persisted", "Jane", "Doe");
    }

    @Test
    void signupPersistsVerifiedUuidAndStandardRoleWithoutPasswordColumns() {
        var response = new SignupService(signupAuth(), service).signup(signupRequest());
        assertThat(response.user().id()).isEqualTo(id);
        assertThat(response.user().role()).isEqualTo(UserRole.STANDARD);
        assertThat(response.session().accessToken()).isEqualTo("isolated-access");
        assertThat(service.getById(id).getEmail()).isEqualTo(EMAIL);
        assertThat(service.getById(id).getFirstName()).isEqualTo("Jane");
        assertThat(users.count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='users'",
                                String.class))
                .noneMatch(name -> name.contains("password") || name.contains("token"));
    }

    @Test
    void signupRetriesSerializationFailureInANewRealTransaction() {
        installSignupFailure(false);
        try {
            var auth = signupAuth();
            var response = new SignupService(auth, service).signup(signupRequest());
            assertThat(response.user().id()).isEqualTo(id);
            assertThat(users.count()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_value FROM public.signup_attempt_seq", Long.class))
                    .isEqualTo(2);
            verify(auth).signup(any());
        } finally {
            removeSignupFailure();
        }
    }

    @Test
    void persistentSignupFailureKeepsAuthIdentityForOperatorRecovery() {
        installSignupFailure(true);
        var auth = signupAuth();
        try {
            assertThatThrownBy(() -> new SignupService(auth, service).signup(signupRequest()))
                    .isInstanceOf(SignupFailure.class)
                    .hasNoCause()
                    .extracting("code")
                    .isEqualTo("ACCOUNT_PROVISIONING_FAILED");
            assertThat(users.count()).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth.users WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_value FROM public.signup_attempt_seq", Long.class))
                    .isEqualTo(2);
            verify(auth).signup(any());
        } finally {
            removeSignupFailure();
        }
        // Authorized reconciliation reuses the known fixture identity in a fresh transaction.
        service.createStandardAccount(id, EMAIL, "Jane", "Doe");
        assertThat(service.getById(id).getRole()).isEqualTo(UserRole.STANDARD);
        assertThat(users.count()).isEqualTo(1);
        verifyNoMoreInteractions(auth);
    }

    @Test
    void uncertainSignupCommitRecoversRealPersistedAccountWithoutAnotherInsert() {
        var boundary = mock(UserService.class);
        when(boundary.createStandardAccount(id, EMAIL, "Jane", "Doe"))
                .thenAnswer(
                        call -> {
                            service.createStandardAccount(id, EMAIL, "Jane", "Doe");
                            throw new TransactionSystemException(
                                    "simulated lost commit acknowledgment");
                        });
        when(boundary.getById(id)).thenAnswer(call -> service.getById(id));
        var response = new SignupService(signupAuth(), boundary).signup(signupRequest());
        assertThat(response.user().id()).isEqualTo(id);
        assertThat(users.count()).isEqualTo(1);
        verify(boundary).createStandardAccount(id, EMAIL, "Jane", "Doe");
    }

    @Test
    void signupEmailConflictNeverLinksToAnotherAuthUuid() {
        UUID existing = UUID.randomUUID();
        authIdentity(existing);
        service.createStandardAccount(existing, EMAIL, "Original", "Account");
        assertThatThrownBy(() -> new SignupService(signupAuth(), service).signup(signupRequest()))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(409);
        assertThat(service.existsById(id)).isFalse();
        assertThat(service.getById(existing).getFirstName()).isEqualTo("Original");
        assertThat(users.count()).isEqualTo(1);
    }

    private void installSignupFailure(boolean persistent) {
        jdbc.execute("CREATE SEQUENCE public.signup_attempt_seq");
        jdbc.execute(
                """
                CREATE FUNCTION public.signup_failure_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE attempt bigint;
                BEGIN
                    attempt := nextval('public.signup_attempt_seq');
                    IF attempt = 1 OR %s THEN
                        RAISE EXCEPTION 'isolated serialization failure' USING ERRCODE = '40001';
                    END IF;
                    RETURN NEW;
                END $$
                """
                        .formatted(persistent ? "TRUE" : "FALSE"));
        jdbc.execute(
                "CREATE TRIGGER signup_failure BEFORE INSERT ON public.users FOR EACH ROW EXECUTE FUNCTION public.signup_failure_fixture()");
    }

    private void removeSignupFailure() {
        jdbc.execute("DROP TRIGGER signup_failure ON public.users");
        jdbc.execute("DROP FUNCTION public.signup_failure_fixture()");
        jdbc.execute("DROP SEQUENCE public.signup_attempt_seq");
    }

    @Test
    void diagnosticPoolReportsRealConnectionAcquisitionWithoutLoggingConnectionDetails(
            CapturedOutput output) {
        try (var pool = new DiagnosticHikariDataSource()) {
            pool.setJdbcUrl(POSTGRES.getJdbcUrl());
            pool.setUsername(POSTGRES.getUsername());
            pool.setPassword(POSTGRES.getPassword());
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            Boolean valid =
                    DatabaseDiagnostics.signup(
                            () ->
                                    DatabaseDiagnostics.operation(
                                            DatabaseDiagnostics.Operation.ACCOUNT_LOOKUP,
                                            1,
                                            () -> {
                                                try (var connection = pool.getConnection()) {
                                                    return connection.isValid(5);
                                                } catch (java.sql.SQLException exception) {
                                                    throw new IllegalStateException(
                                                            "Isolated test connection failed");
                                                }
                                            }));
            assertThat(valid).isTrue();
        }
        String diagnostics =
                output.getAll()
                        .lines()
                        .filter(line -> line.contains("db.diagnostic"))
                        .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(diagnostics)
                .contains(
                        "operation=connection.acquire parentOperation=account.lookup attempt=1 event=success connectionAcquired=true",
                        "operation=account.lookup parentOperation=none attempt=1 event=success connectionAcquired=true")
                .doesNotContain(POSTGRES.getJdbcUrl(), "jdbc:postgresql:", id.toString(), EMAIL);
    }
}
