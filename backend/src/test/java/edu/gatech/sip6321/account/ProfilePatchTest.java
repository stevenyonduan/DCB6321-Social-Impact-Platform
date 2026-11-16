package edu.gatech.sip6321.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfilePatchTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UserRepository repository = mock(UserRepository.class);
    private final UserService service = new UserService(repository);
    private final UUID id = UUID.randomUUID();

    @Test
    void partialUpdatesPreserveValuesAndExplicitNullClearsBio() {
        var user = User.standardAccount(id, "student@example.test", "First", "Last");
        when(repository.findUserById(id)).thenReturn(Optional.of(user));
        service.updateProfile(id, patch("{\"bio\":\"Gardens\"}"));
        service.updateProfile(id, patch("{\"firstName\":\" Jordan \"}"));
        assertThat(user.getFirstName()).isEqualTo(" Jordan ");
        assertThat(user.getLastName()).isEqualTo("Last");
        assertThat(user.getBio()).isEqualTo("Gardens");
        service.updateProfile(id, patch("{\"lastName\":\"Taylor\"}"));
        assertThat(user.getFirstName()).isEqualTo(" Jordan ");
        assertThat(user.getLastName()).isEqualTo("Taylor");
        assertThat(user.getBio()).isEqualTo("Gardens");
        service.updateProfile(id, patch("{\"bio\":null}"));
        assertThat(user.getBio()).isNull();
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getEmail()).isEqualTo("student@example.test");
        assertThat(user.getRole()).isEqualTo(UserRole.STANDARD);
        verify(repository, times(4)).flush();
    }

    @Test
    void invalidUpdateCannotApplyPartialChanges() {
        var user = User.standardAccount(id, "student@example.test", "First", "Last");
        assertThatThrownBy(
                        () ->
                                service.updateProfile(
                                        id, patch("{\"firstName\":\"Changed\",\"lastName\":null}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getFirstName()).isEqualTo("First");
        assertThatThrownBy(() -> service.updateProfile(id, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateProfile(null, patch("{\"bio\":null}")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsFullUnicodeLimitAndDoesNotInventBioColumnLimit() {
        var user = User.standardAccount(id, "student@example.test", "First", "Last");
        when(repository.findUserById(id)).thenReturn(Optional.of(user));
        String name = "🌱".repeat(100);
        String bio = "b".repeat(20000);
        var patch =
                patch(
                        "{\"firstName\":\""
                                + name
                                + "\",\"lastName\":\""
                                + name
                                + "\",\"bio\":\""
                                + bio
                                + "\"}");
        service.updateProfile(id, patch);
        assertThat(user.getFirstName()).isEqualTo(name);
        assertThat(user.getLastName()).isEqualTo(name);
        assertThat(user.getBio()).isEqualTo(bio);
        assertThat(patch.toString()).isEqualTo("ProfilePatch[redacted]");
    }

    private ProfilePatch patch(String body) {
        return ProfilePatch.fromJson(json.readTree(body));
    }
}
