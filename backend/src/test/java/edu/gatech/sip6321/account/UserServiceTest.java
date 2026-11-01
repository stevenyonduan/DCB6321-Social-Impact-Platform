package edu.gatech.sip6321.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class UserServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = new UserService(users);
    private final UUID id = UUID.randomUUID();
    private static final String EMAIL = "volunteer@example.test";

    @Test
    void createsStandardAccountWithSuppliedAuthIdentityAndNullableProfile() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        User user = service.createStandardAccount(id, EMAIL, "First", "Last");
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getRole()).isEqualTo(UserRole.STANDARD);
        assertThat(user.getFirstName()).isEqualTo("First");
        assertThat(user.getLastName()).isEqualTo("Last");
        assertThat(user.getBio()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.getOrganizationId()).isNull();
        assertThat(user.getCreatedAt()).isNull(); // Database generates it, not the service.
        assertThat(user.isNew()).isTrue();
        verify(users).saveAndFlush(user);
    }

    @Test
    void retrievesByIdAndExactEmail() {
        User user = User.standardAccount(id, EMAIL, "First", "Last");
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        assertThat(service.getById(id)).isSameAs(user);
        assertThat(service.getByEmail(EMAIL)).isSameAs(user);
    }

    @Test
    void missingAccountsHaveSanitizedErrors() {
        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");
        assertThatThrownBy(() -> service.getByEmail(EMAIL))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void checksExistenceByBothKeys(boolean exists) {
        when(users.existsById(id)).thenReturn(exists);
        when(users.existsByEmail(EMAIL)).thenReturn(exists);
        assertThat(service.existsById(id)).isEqualTo(exists);
        assertThat(service.existsByEmail(EMAIL)).isEqualTo(exists);
    }

    @Test
    void rejectsExistingUuidBeforeSaving() {
        when(users.existsById(id)).thenReturn(true);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", "Last"))
                .isInstanceOf(DuplicateAccountException.class);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void rejectsExistingEmailBeforeSaving() {
        when(users.existsByEmail(EMAIL)).thenReturn(true);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", "Last"))
                .isInstanceOf(DuplicateAccountException.class);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource({"23505,users_pkey", "23505,users_email_key"})
    void translatesConcurrentUniqueViolationWithoutLeakingDriverDetails(String state, String name) {
        when(users.saveAndFlush(any())).thenThrow(violation(state, name));
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", "Last"))
                .isInstanceOf(DuplicateAccountException.class)
                .hasMessage("Account already exists")
                .hasNoCause();
    }

    @ParameterizedTest
    @CsvSource({"23503,users_auth_user_fkey", "23514,users_role_check", "23505,other_unique"})
    void doesNotMisclassifyOtherIntegrityFailures(String state, String name) {
        var failure = violation(state, name);
        when(users.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", "Last"))
                .isSameAs(failure);
    }

    @Test
    void rejectsMissingIdentityAndNullOrOversizedRequiredFieldsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.createStandardAccount(null, EMAIL, "First", "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, null, "First", "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> service.createStandardAccount(id, "x".repeat(256), "First", "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, null, "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "x".repeat(101), "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", "x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.existsById(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getByEmail(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.existsByEmail("x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t", "\r\n", " \t\n", "\u2003"})
    void rejectsEmptyAndWhitespaceOnlyRequiredFieldsBeforeDatabaseAccess(String value) {
        assertThatThrownBy(() -> service.createStandardAccount(id, value, "First", "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, value, "Last"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createStandardAccount(id, EMAIL, "First", value))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getByEmail(value))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.existsByEmail(value))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(users);
    }

    @Test
    void preservesExactValidValuesForCreationAndEmailQueries() {
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        String email = " MixedCase@example.test ";
        User user = service.createStandardAccount(id, email, " First ", " Last ");
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getFirstName()).isEqualTo(" First ");
        assertThat(user.getLastName()).isEqualTo(" Last ");
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(users.existsByEmail(email)).thenReturn(true);
        assertThat(service.getByEmail(email)).isSameAs(user);
        assertThat(service.existsByEmail(email)).isTrue();
        verify(users).findByEmail(email);
        verify(users, org.mockito.Mockito.times(2)).existsByEmail(email);
    }

    @Test
    void preservesExactEmailAndAllowsFullPostgresCharacterLimits() {
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        String email = "MixedCase@example.test";
        String name = "\uD83C\uDF31".repeat(100);
        User user = service.createStandardAccount(id, email, name, "x".repeat(100));
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getFirstName()).isEqualTo(name);
    }

    private DataIntegrityViolationException violation(String state, String constraint) {
        return new DataIntegrityViolationException(
                "sensitive driver details",
                new ConstraintViolationException(
                        "sensitive driver details",
                        new SQLException("sensitive driver details", state),
                        constraint));
    }
}
