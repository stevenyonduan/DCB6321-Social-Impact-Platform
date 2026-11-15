package edu.gatech.sip6321.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrganizationAuthorizationTest {
    @Test
    void adminEmailRejectsInvalidUnicodeWithoutChangingExactValidEmail() {
        for (String email :
                List.of(
                        "bad" + (char) 0xD800 + "@example.test",
                        "bad" + (char) 0xDC00 + "@example.test",
                        "bad" + (char) 0 + "@example.test")) {
            assertThatThrownBy(() -> new AddAdminRequest(email))
                    .isInstanceOf(OrganizationFailure.class);
        }
        assertThat(new AddAdminRequest("Mike@Example.test").email()).isEqualTo("Mike@Example.test");
        assertThat(new AddAdminRequest("Mike@Example.test").toString()).doesNotContain("Mike");
    }

    @Test
    void organizationAuthorityIsIndependentOfEveryGlobalRole() {
        var profiles = mock(ProfileService.class);
        var authorization = new OrganizationAuthorization(profiles);
        var headers = List.of("Bearer access");
        var org = UUID.randomUUID();
        for (var global : UserRole.values())
            for (var role :
                    new OrganizationRole[] {null, OrganizationRole.OWNER, OrganizationRole.ADMIN})
                for (var association : new UUID[] {null, org}) {
                    var profile =
                            new ProfileResponse(
                                    UUID.randomUUID(),
                                    "a@example.test",
                                    global,
                                    "A",
                                    "B",
                                    null,
                                    null,
                                    association,
                                    role,
                                    null);
                    when(profiles.me(headers)).thenReturn(profile);
                    if (role == null || association == null) {
                        assertThatThrownBy(() -> authorization.manager(headers))
                                .isInstanceOf(OrganizationFailure.class);
                    } else {
                        assertThat(authorization.manager(headers, org)).isEqualTo(profile);
                        assertThatThrownBy(() -> authorization.manager(headers, UUID.randomUUID()))
                                .isInstanceOf(OrganizationFailure.class);
                        if (role == OrganizationRole.OWNER)
                            assertThat(authorization.owner(headers)).isEqualTo(profile);
                        else
                            assertThatThrownBy(() -> authorization.owner(headers))
                                    .isInstanceOf(OrganizationFailure.class);
                    }
                }
    }
}
