package edu.gatech.sip6321.account.interests;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.OrganizationManagementMigrations;
import edu.gatech.sip6321.auth.SupabaseAuthClient;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("database")
@AutoConfigureMockMvc
@SpringBootTest(properties = "SIP_AUTH_ENABLED=true")
class InterestPersistenceIT {
    static final String PERSONAL = "/api/users/me/interests";
    static final String ORGANIZATION = "/api/organizations/me/interests";
    static final UUID FIRST = UUID.fromString("28c47059-08bf-421b-843e-4ce33c79b665");
    static final UUID SECOND = UUID.fromString("96c3fda8-27c1-4da5-8a8b-c82e5e42b123");

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

    @Autowired JdbcTemplate jdbc;
    @Autowired InterestService interests;
    @Autowired MockMvc mvc;
    @MockitoBean SupabaseAuthClient auth;

    @BeforeAll
    static void migrate(@Autowired DataSource source, @Autowired JdbcTemplate jdbc)
            throws Exception {
        new ResourceDatabasePopulator(
                        new FileSystemResource("db/migrations/V001__create_users.sql"),
                        new FileSystemResource("db/migrations/V002__create_organizations.sql"),
                        new FileSystemResource("db/migrations/V003__create_events.sql"))
                .execute(source);
        OrganizationManagementMigrations.apply(source);
        var existingOrganization = organization(jdbc);
        insertAccount(jdbc, existingOrganization, "OWNER");
        var existingAccounts = jdbc.queryForList("SELECT * FROM public.users");
        var existingOrganizations = jdbc.queryForList("SELECT * FROM public.organizations");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            for (String name :
                    List.of(
                            "V005__create_event_registrations.sql",
                            "V006__create_social_posts.sql",
                            "V007__create_interests.sql")) {
                statement.execute(Files.readString(Path.of("db/migrations", name)));
            }
        }
        assertThat(jdbc.queryForList("SELECT * FROM public.users")).isEqualTo(existingAccounts);
        assertThat(jdbc.queryForList("SELECT * FROM public.organizations"))
                .isEqualTo(existingOrganizations);
    }

    @Test
    void catalogIsAvailableThroughTheApiAndBrowserRolesHaveNoDirectAccess() throws Exception {
        account("volunteer", null, null);
        mvc.perform(get("/api/interests").header("Authorization", "Bearer volunteer"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].name")
                                .value(
                                        contains(
                                                "Animal Welfare",
                                                "Arts & Culture",
                                                "Civic Engagement",
                                                "Community Support",
                                                "Disability & Accessibility",
                                                "Disaster Relief & Preparedness",
                                                "Education & Youth",
                                                "Employment & Career Readiness",
                                                "Environment & Outdoors",
                                                "Food Access",
                                                "Health & Wellness",
                                                "Housing & Homelessness",
                                                "Immigrant & Refugee Support",
                                                "Older Adults",
                                                "Social Justice & Advocacy",
                                                "Sports & Recreation",
                                                "Technology & Digital Literacy",
                                                "Veterans & Military Families")));
        for (String table : List.of("interests", "user_interests", "organization_interests")) {
            assertThat(
                            jdbc.queryForObject(
                                    """
                    SELECT relrowsecurity FROM pg_class WHERE oid = CAST(? AS regclass)
                    """,
                                    Boolean.class,
                                    "public." + table))
                    .isTrue();
            for (String role : List.of("anon", "authenticated")) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT has_table_privilege(?, ?, 'SELECT, INSERT, UPDATE, DELETE')",
                                        Boolean.class,
                                        role,
                                        "public." + table))
                        .isFalse();
            }
        }
    }

    @Test
    void personalSelectionsReplaceClearAndRollBackOnUnknownIds() throws Exception {
        account("first", null, null);
        account("another", null, null);
        save(PERSONAL, "first", json(FIRST, FIRST, SECOND), 200)
                .andExpect(jsonPath("$.length()").value(2));
        save(PERSONAL, "first", json(FIRST, SECOND), 200)
                .andExpect(jsonPath("$.length()").value(2));
        save(PERSONAL, "first", json(FIRST, UUID.randomUUID()), 400)
                .andExpect(jsonPath("$.code").value("UNKNOWN_INTEREST"));
        list(PERSONAL, "first").andExpect(jsonPath("$.length()").value(2));
        list(PERSONAL, "another").andExpect(content().json("[]"));
        save(PERSONAL, "first", json(SECOND), 200)
                .andExpect(jsonPath("$[0].id").value(SECOND.toString()));
        save(PERSONAL, "first", json(), 200).andExpect(content().json("[]"));
        list(PERSONAL, "first").andExpect(content().json("[]"));
    }

    @Test
    void organizationManagersShareSelectionsAndOtherAccountsCannotModifyThem() throws Exception {
        var organization = organization(jdbc);
        account("owner", organization, "OWNER");
        var admin = account("admin", organization, "ADMIN");
        var outsider = account("outsider", null, null);
        jdbc.update("UPDATE public.users SET role = 'ADMIN' WHERE id = ?", outsider);
        account("another-owner", organization(jdbc), "OWNER");

        save(ORGANIZATION, "owner", json(FIRST, SECOND), 200);
        list(ORGANIZATION, "admin").andExpect(jsonPath("$.length()").value(2));
        save(ORGANIZATION, "admin", json(SECOND), 200);
        save(ORGANIZATION, "outsider", json(), 403);
        list(ORGANIZATION, "another-owner").andExpect(content().json("[]"));
        jdbc.update(
                "UPDATE public.users SET organization_id=NULL, organization_role=NULL WHERE id=?",
                admin);
        save(ORGANIZATION, "admin", json(), 403);
        list(ORGANIZATION, "owner").andExpect(jsonPath("$[0].id").value(SECOND.toString()));
        save(ORGANIZATION, "owner", json(), 200);
        list(ORGANIZATION, "owner").andExpect(content().json("[]"));
    }

    @Test
    void signedInVisitorsCanReadOnlyTheSelectedOrganizationTags() throws Exception {
        var organization = organization(jdbc);
        var another = organization(jdbc);
        account("owner", organization, "OWNER");
        account("visitor", another, "ADMIN");
        save(ORGANIZATION, "owner", json(FIRST), 200);
        save(ORGANIZATION, "visitor", json(SECOND), 200);
        var route = "/api/organizations/" + organization + "/interests";

        list(route, "visitor")
                .andExpect(
                        content()
                                .json(
                                        """
                        [{"id":"%s","name":"Animal Welfare"}]
                        """
                                                .formatted(FIRST),
                                        org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/organizations/" + UUID.randomUUID() + "/interests")
                                .header("Authorization", "Bearer visitor"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    }

    @Test
    void simultaneousOrganizationReplacementsLeaveOneCompleteSelection() throws Exception {
        var organization = organization(jdbc);
        account("owner", organization, "OWNER");
        account("admin", organization, "ADMIN");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return interests.replaceOrganizationSelections(
                                        List.of("Bearer owner"),
                                        new InterestSelection(Set.of(FIRST)));
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return interests.replaceOrganizationSelections(
                                        List.of("Bearer admin"),
                                        new InterestSelection(Set.of(SECOND)));
                            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS))
                    .extracting(InterestOption::id)
                    .containsExactly(FIRST);
            assertThat(second.get(10, TimeUnit.SECONDS))
                    .extracting(InterestOption::id)
                    .containsExactly(SECOND);
            assertThat(interests.organizationSelections(List.of("Bearer owner")))
                    .extracting(InterestOption::id)
                    .hasSize(1)
                    .allMatch(Set.of(FIRST, SECOND)::contains);
        }
    }

    private static UUID organization(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "INSERT INTO public.organizations (name) VALUES ('Test Organization') RETURNING id",
                UUID.class);
    }

    private UUID account(String token, UUID organization, String role) {
        var id = insertAccount(jdbc, organization, role);
        when(auth.verifyAccessToken(token)).thenReturn(id);
        return id;
    }

    private static UUID insertAccount(JdbcTemplate jdbc, UUID organization, String role) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.users (id) VALUES (?)", id);
        jdbc.update(
                """
                INSERT INTO public.users (id,email,role,first_name,last_name,organization_id,organization_role)
                VALUES (?,?,'STANDARD','Test','Volunteer',?,?)
                """,
                id,
                id + "@example.test",
                organization,
                role);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions list(String route, String token)
            throws Exception {
        return mvc.perform(get(route).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions save(
            String route, String token, String json, int expectedStatus) throws Exception {
        return mvc.perform(
                        put(route)
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(json))
                .andExpect(status().is(expectedStatus));
    }

    private static String json(UUID... ids) {
        return "{\"interestIds\":["
                + String.join(",", Arrays.stream(ids).map(id -> "\"" + id + "\"").toList())
                + "]}";
    }
}
