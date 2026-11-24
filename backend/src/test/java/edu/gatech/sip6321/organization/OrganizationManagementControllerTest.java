package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = OrganizationManagementController.class,
        properties = "SIP_AUTH_ENABLED=true")
@Import({
    OrganizationManagementService.class,
    OrganizationAuthorization.class,
    ProfileService.class,
    OrganizationExceptionHandler.class,
    ManagementAttemptLimiter.class
})
class OrganizationManagementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService accounts;
    @MockitoBean UserRepository users;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean JdbcTemplate jdbc;
    UUID actorId, org, targetId;
    User actor, target;
    static final String TEAM = "/api/organizations/me/admins";

    @BeforeEach
    void setup() {
        actorId = UUID.randomUUID();
        org = UUID.randomUUID();
        targetId = UUID.randomUUID();
        actor = mock(User.class);
        target = mock(User.class);
        when(actor.getId()).thenReturn(actorId);
        when(actor.getRole()).thenReturn(UserRole.STANDARD);
        when(actor.getOrganizationId()).thenReturn(org);
        when(actor.getOrganizationRole()).thenReturn(OrganizationRole.OWNER);
        when(auth.verifyAccessToken("access")).thenReturn(actorId);
        when(accounts.getById(actorId)).thenReturn(actor);
        when(organizations.existsById(org)).thenReturn(true);
        when(target.getId()).thenReturn(targetId);
        when(users.findByEmail("mike@example.test")).thenReturn(Optional.of(target));
        when(users.claimOrganization(targetId, org, OrganizationRole.ADMIN)).thenReturn(1);
    }

    @Test
    void createsWithOwnershipAndLocationWithoutChangingGlobalRole() throws Exception {
        when(actor.getOrganizationId()).thenReturn(null);
        when(actor.getOrganizationRole()).thenReturn(null);
        var created = mock(Organization.class);
        when(created.getId()).thenReturn(org);
        when(created.getName()).thenReturn("Habitat");
        when(organizations.saveAndFlush(any())).thenReturn(created);
        when(users.claimOrganization(actorId, org, OrganizationRole.OWNER)).thenReturn(1);
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"name\":\"Habitat\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/organizations/" + org))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organization.id").value(org.toString()))
                .andExpect(jsonPath("$.organization.isVerified").value(false))
                .andExpect(jsonPath("$.ownerId").value(actorId.toString()))
                .andExpect(jsonPath("$.organizationRole").value("OWNER"));
        verify(users).claimOrganization(actorId, org, OrganizationRole.OWNER);
        assertThat(actor.getRole()).isEqualTo(UserRole.STANDARD);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ownerId",
                "organizationId",
                "organizationRole",
                "isVerified",
                "role",
                "id",
                "createdAt"
            })
    void creationRejectsProtectedFields(String field) throws Exception {
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"name\":\"Habitat\",\"" + field + "\":null}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users, organizations, jdbc);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[]",
                "null",
                "{\"name\":null}",
                "{\"name\":5}",
                "{\"name\":\" \"}",
                "{\"name\":\"Habitat\",\"website\":\"javascript:bad\"}"
            })
    void rejectsInvalidCreation(String body) throws Exception {
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users, organizations, jdbc);
    }

    @Test
    void existingMembershipConflictsBeforeCreating() throws Exception {
        mvc.perform(
                        post("/api/organizations")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"name\":\"Habitat\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(organizations, users, jdbc);
    }

    @Test
    void ownerAddsWithoutReturningPersonalData() throws Exception {
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"email\":\"mike@example.test\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(users).claimOrganization(targetId, org, OrganizationRole.ADMIN);
        verify(jdbc).update(anyString(), eq(actorId), eq(targetId), eq(org), eq("ADMIN_ADDED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "same", "other"})
    void unavailableTargetsHaveIdenticalSafeConflict(String kind) throws Exception {
        if (kind.equals("missing"))
            when(users.findByEmail("mike@example.test")).thenReturn(Optional.empty());
        else when(users.claimOrganization(targetId, org, OrganizationRole.ADMIN)).thenReturn(0);
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"email\":\"mike@example.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_CONFLICT"));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "STANDARD", "GLOBAL_ADMIN"})
    void nonOwnersCannotAddOrRemove(String kind) throws Exception {
        when(actor.getOrganizationRole())
                .thenReturn(kind.equals("ADMIN") ? OrganizationRole.ADMIN : null);
        if (kind.equals("GLOBAL_ADMIN")) when(actor.getRole()).thenReturn(UserRole.ADMIN);
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"email\":\"mike@example.test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(TEAM + "/" + targetId).header("Authorization", "Bearer access"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, organizations, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN"})
    void managersListOwnTeamWithAdminDetailsVisibleOnlyToOwner(String role) throws Exception {
        when(actor.getOrganizationRole()).thenReturn(OrganizationRole.valueOf(role));
        when(target.getOrganizationRole()).thenReturn(OrganizationRole.ADMIN);
        when(target.getFirstName()).thenReturn("Mike");
        when(target.getLastName()).thenReturn("Volunteer");
        when(target.getEmail()).thenReturn("mike@example.test");
        when(target.getAvatarUrl()).thenReturn("https://example.test/mike.png");
        var owner = mock(User.class);
        when(owner.getOrganizationRole()).thenReturn(OrganizationRole.OWNER);
        when(owner.getEmail()).thenReturn("owner@example.test");
        when(owner.getAvatarUrl()).thenReturn("https://example.test/owner.png");
        when(users.findByOrganizationIdOrderById(org)).thenReturn(List.of(target, owner));
        var result =
                mvc.perform(get(TEAM).header("Authorization", "Bearer access"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].id").value(targetId.toString()))
                        .andExpect(jsonPath("$[0].firstName").value("Mike"))
                        .andExpect(jsonPath("$[0].organizationRole").value("ADMIN"))
                        .andExpect(jsonPath("$[1].email").doesNotHaveJsonPath())
                        .andExpect(jsonPath("$[1].avatarUrl").doesNotHaveJsonPath());
        if (role.equals("OWNER")) {
            result.andExpect(jsonPath("$[0].email").value("mike@example.test"))
                    .andExpect(jsonPath("$[0].avatarUrl").value("https://example.test/mike.png"));
        } else {
            result.andExpect(jsonPath("$[0].email").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$[0].avatarUrl").doesNotHaveJsonPath());
        }
        verify(users).findByOrganizationIdOrderById(org);
    }

    @Test
    void ownerRemovesAdminAndAudits() throws Exception {
        when(users.removeAdmin(targetId, org)).thenReturn(1);
        mvc.perform(delete(TEAM + "/" + targetId).header("Authorization", "Bearer access"))
                .andExpect(status().isNoContent());
        verify(jdbc).update(anyString(), eq(actorId), eq(targetId), eq(org), eq("ADMIN_REMOVED"));
    }

    @Test
    void removalDoesNotDiscloseOtherMembershipsAndCannotRemoveOwner() throws Exception {
        mvc.perform(delete(TEAM + "/" + targetId).header("Authorization", "Bearer access"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_NOT_FOUND"));
        mvc.perform(delete(TEAM + "/" + actorId).header("Authorization", "Bearer access"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }

    @Test
    void allNewRoutesRequireVerifiedAuthentication() throws Exception {
        when(auth.verifyAccessToken("access")).thenThrow(ProfileFailure.unauthorized());
        for (boolean expired : List.of(false, true)) {
            for (var request :
                    List.of(
                            post("/api/organizations")
                                    .contentType("application/json")
                                    .content("{\"name\":\"Habitat\"}"),
                            post(TEAM)
                                    .contentType("application/json")
                                    .content("{\"email\":\"mike@example.test\"}"),
                            get(TEAM),
                            delete(TEAM + "/" + targetId))) {
                if (expired) request.header("Authorization", "Bearer access");
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"));
            }
        }
        verifyNoInteractions(users, organizations, jdbc);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[]",
                "{\"email\":123}",
                "{\"email\":\"bad\"}",
                "{\"email\":null}",
                "{\"email\":\"mike@example.test\",\"role\":\"OWNER\"}"
            })
    void rejectsInvalidAdminBodies(String body) throws Exception {
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users, jdbc);
    }

    @Test
    void failedEmailLookupsAreRateLimited() throws Exception {
        when(users.findByEmail("mike@example.test")).thenReturn(Optional.empty());
        for (int i = 0; i < 10; i++)
            mvc.perform(
                            post(TEAM)
                                    .header("Authorization", "Bearer access")
                                    .contentType("application/json")
                                    .content("{\"email\":\"mike@example.test\"}"))
                    .andExpect(status().isConflict());
        mvc.perform(
                        post(TEAM)
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"email\":\"mike@example.test\"}"))
                .andExpect(status().isTooManyRequests());
        verify(users, times(10)).findByEmail("mike@example.test");
    }
}
