package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.auth.SignupFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(OutputCaptureExtension.class)
class LoginServiceTest {
    private final SupabaseAuthClient auth = mock(SupabaseAuthClient.class);
    private final UserService users = mock(UserService.class);
    private final LoginService service = new LoginService(auth, users);
    private final LoginRequest request = new LoginRequest(EMAIL, PASSWORD);

    private void authenticated() {
        when(auth.login(request))
                .thenReturn(new SupabaseAuthClient.VerifiedLogin(ID, EMAIL, identity().session()));
    }

    @Test
    void successOnlyReadsByVerifiedUuid(CapturedOutput output) {
        authenticated();
        when(users.getById(ID)).thenReturn(account());
        var result = service.login(request);
        assertThat(result.user().id()).isEqualTo(ID);
        assertThat(result.user().role().name()).isEqualTo("STANDARD");
        assertThat(result.session().accessToken()).isEqualTo("access-never-log");
        var order = inOrder(auth, users);
        order.verify(auth).login(request);
        order.verify(users).getById(ID);
        verifyNoMoreInteractions(auth, users);
        assertThat(output.getAll() + request + result)
                .doesNotContain(PASSWORD, "access-never-log", "refresh-never-log");
    }

    @Test
    void authFailureNeverTouchesAccounts() {
        when(auth.login(request)).thenThrow(LoginFailure.credentials());
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(LoginFailure.class)
                .extracting("code")
                .isEqualTo("INVALID_CREDENTIALS");
        verifyNoInteractions(users);
    }

    @Test
    void missingAccountIsNeverProvisioned() {
        authenticated();
        when(users.getById(ID)).thenThrow(new AccountNotFoundException());
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(LoginFailure.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("ACCOUNT_PROVISIONING_REQUIRED");
        verify(users).getById(ID);
        verifyNoMoreInteractions(users);
    }

    @Test
    void databaseFailuresAreSanitizedWithoutRetriesOrWrites(CapturedOutput output) {
        authenticated();
        String secret =
                PASSWORD
                        + " access-never-log refresh-never-log sb_publishable_private jdbc:postgresql://private/db";
        for (RuntimeException failure :
                new RuntimeException[] {
                    new DataAccessResourceFailureException(secret),
                            new InvalidDataAccessResourceUsageException(secret),
                    new CannotCreateTransactionException(secret),
                            new TransactionSystemException(secret)
                }) {
            doThrow(failure).when(users).getById(ID);
            assertThatThrownBy(() -> service.login(request))
                    .isInstanceOf(LoginFailure.class)
                    .hasNoCause()
                    .hasMessageNotContaining(secret)
                    .extracting("code")
                    .isEqualTo("ACCOUNT_UNAVAILABLE");
        }
        verify(users, times(4)).getById(ID);
        verifyNoMoreInteractions(users);
        assertThat(output.getAll())
                .contains("operation=account.lookup", "event=failure")
                .doesNotContain(
                        PASSWORD,
                        "access-never-log",
                        "refresh-never-log",
                        "sb_publishable_private",
                        "jdbc:postgresql:");
    }

    @Test
    void expiredSessionAfterLookupIsNotReleased() {
        when(auth.login(request))
                .thenReturn(
                        new SupabaseAuthClient.VerifiedLogin(
                                ID,
                                EMAIL,
                                new SupabaseAuthClient.Session(
                                        "access-never-log",
                                        "refresh-never-log",
                                        "bearer",
                                        3600,
                                        1)));
        when(users.getById(ID)).thenReturn(account());
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(LoginFailure.class)
                .extracting("code")
                .isEqualTo("AUTH_RESPONSE_INVALID");
        verify(users).getById(ID);
        verifyNoMoreInteractions(users);
    }
}
