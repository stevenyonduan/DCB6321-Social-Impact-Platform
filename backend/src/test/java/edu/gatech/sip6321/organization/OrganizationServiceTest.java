package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.auth.ProfileService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrganizationServiceTest {
    private final ProfileService profiles = mock(ProfileService.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationService service =
            new OrganizationService(
                    profiles,
                    organizations,
                    mock(UserService.class),
                    mock(OrganizationProvisioningService.class));
    private final UUID id = UUID.randomUUID();
    private final List<String> authorization = List.of("Bearer unit-access");

    @Test
    void serviceCallsRequireAuthenticationWithoutController() {
        when(profiles.me(authorization)).thenThrow(ProfileFailure.unauthorized());
        assertThatThrownBy(() -> service.get(authorization, id)).isInstanceOf(ProfileFailure.class);
        assertThatThrownBy(() -> service.me(authorization)).isInstanceOf(ProfileFailure.class);
        assertThatThrownBy(() -> service.patch(authorization, id, null))
                .isInstanceOf(ProfileFailure.class);
        verifyNoInteractions(organizations);
    }

    @Test
    void persistedRoleAndAssociationAreRequiredForDirectServiceCalls() {
        for (UserRole role : UserRole.values()) {
            for (UUID association : new UUID[] {null, id, UUID.randomUUID()}) {
                var account =
                        new ProfileResponse(
                                UUID.randomUUID(),
                                "test@example.test",
                                role,
                                "First",
                                "Last",
                                null,
                                null,
                                association,
                                null);
                when(profiles.me(authorization)).thenReturn(account);
                if (role == UserRole.ORGANIZATION && id.equals(association)) continue;
                assertThatThrownBy(() -> service.patch(authorization, id, null))
                        .isInstanceOf(OrganizationFailure.class)
                        .extracting("status")
                        .isEqualTo(403);
            }
        }
        verifyNoInteractions(organizations);
    }

    @Test
    void internalCreationValidatesBeforeSaving() {
        var provisioning = new OrganizationProvisioningService(organizations);
        assertThatThrownBy(() -> provisioning.create(" ", null, null))
                .isInstanceOf(OrganizationFailure.class);
        assertThatThrownBy(() -> provisioning.create("Valid", null, "javascript:alert(1)"))
                .isInstanceOf(OrganizationFailure.class);
        verifyNoInteractions(organizations);
    }

    @Test
    void immutablePatchPreservesVerificationAndNoopValues() {
        var organization = Organization.create("Original", null, null);
        organization.apply(OrganizationPatch.fromJson(JsonMapper.builder().build().readTree("{}")));
        assertThat(organization.getName()).isEqualTo("Original");
        assertThat(organization.isVerified()).isFalse();
        assertThat(organization.getDescription()).isNull();
    }
}
