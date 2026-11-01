package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.auth.SignupFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.DuplicateAccountException;
import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.account.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class SignupServiceTest {
    private final SupabaseAuthClient auth = mock(SupabaseAuthClient.class);
    private final UserService users = mock(UserService.class);
    private final SignupService service = new SignupService(auth, users);

    private void ready() {
        when(auth.signup(REQUEST)).thenReturn(identity());
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe")).thenReturn(account());
    }

    @Test
    void returnsAllowlistedAccountAndSessionAfterPersistence() {
        ready();
        var result = service.signup(REQUEST);
        assertThat(result.user().id()).isEqualTo(ID);
        assertThat(result.user().role()).isEqualTo(UserRole.STANDARD);
        assertThat(result.session().accessToken()).isEqualTo("access-never-log");
        var order = inOrder(auth, users);
        order.verify(auth).signup(REQUEST);
        order.verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verifyNoMoreInteractions(users, auth);
    }

    @Test
    void rejectedOrDuplicateAuthNeverTouchesDatabase() {
        when(auth.signup(REQUEST)).thenThrow(SignupFailure.conflict());
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(409);
        verifyNoInteractions(users);
    }

    @Test
    void transientFailureRetriesOnceWithSameTrustedIdentity() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new TransientDataAccessResourceException("sensitive"))
                .thenReturn(account());
        when(users.getById(ID)).thenThrow(new AccountNotFoundException());
        assertThat(service.signup(REQUEST).user().id()).isEqualTo(ID);
        verify(users, times(2)).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(auth).signup(REQUEST);
    }

    @Test
    void uncertainCommitReadsExistingAccountWithoutInsertingAgainOrOverwriting() {
        ready();
        var existing = account();
        when(existing.getFirstName()).thenReturn("Original");
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new TransactionSystemException("uncertain sensitive commit"));
        when(users.getById(ID)).thenReturn(existing);
        assertThat(service.signup(REQUEST).user().firstName()).isEqualTo("Original");
        verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(auth).signup(REQUEST);
    }

    @Test
    void persistentConnectionFailureHasBoundedRetryAndNoTokensOrCause() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new CannotCreateTransactionException("secret driver details"));
        when(users.getById(ID)).thenThrow(new AccountNotFoundException());
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .hasMessageNotContaining("secret")
                .extracting("code")
                .isEqualTo("ACCOUNT_PROVISIONING_FAILED");
        verify(users, times(2)).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(auth).signup(REQUEST);
    }

    @Test
    void failureDuringReconciliationReadDoesNotLoop() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new DataAccessResourceFailureException("sensitive"));
        when(users.getById(ID)).thenThrow(new DataAccessResourceFailureException("sensitive"));
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(503);
        verify(users).getById(ID);
        verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(auth).signup(REQUEST);
    }

    @Test
    void nonTransientIntegrityFailureIsNotRetried() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new DataIntegrityViolationException("sensitive FK details"));
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .extracting("status")
                .isEqualTo(503);
        verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(users, never()).getById(any());
    }

    @Test
    void duplicateRaceCanOnlyRecoverTheSameStandardIdentity() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new DuplicateAccountException());
        when(users.getById(ID)).thenReturn(account());
        assertThat(service.signup(REQUEST).user().id()).isEqualTo(ID);
        verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
    }

    @Test
    void emailConflictWithAnotherIdentityCannotRecover() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new DuplicateAccountException());
        when(users.getById(ID)).thenThrow(new AccountNotFoundException());
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(409);
        verify(users, never()).getByEmail(any());
    }

    @Test
    void recoveredAccountWithDifferentEmailOrPrivilegeIsNotAccepted() {
        ready();
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new DuplicateAccountException());
        var existing = account();
        when(users.getById(ID)).thenReturn(existing);
        when(existing.getEmail()).thenReturn("other@example.test");
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(409);
        when(existing.getEmail()).thenReturn(EMAIL);
        when(existing.getRole()).thenReturn(UserRole.ADMIN);
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("status")
                .isEqualTo(409);
    }

    @Test
    void sensitiveObjectsHaveRedactedStringRepresentations() {
        assertThat(
                        REQUEST.toString()
                                + identity()
                                + identity().session()
                                + SignupResponse.of(account(), identity().session()))
                .doesNotContain(PASSWORD, "access-never-log", "refresh-never-log", EMAIL);
    }
}
