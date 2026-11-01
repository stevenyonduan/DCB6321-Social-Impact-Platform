package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.auth.SignupFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.diagnostics.DatabaseDiagnostics;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(OutputCaptureExtension.class)
class SignupDiagnosticsTest {
    private final SupabaseAuthClient auth = mock(SupabaseAuthClient.class);
    private final UserService users = mock(UserService.class);
    private final SignupService service = new SignupService(auth, users);
    private static final Pattern ID_PATTERN = Pattern.compile("correlationId=([0-9a-f-]{36})");

    @Test
    void logsBothInitializationFailuresWithSameCorrelationAndNoSensitiveDetails(
            CapturedOutput output) {
        when(auth.signup(REQUEST)).thenReturn(identity());
        String privateMessage =
                PASSWORD
                        + " access-never-log refresh-never-log sb_publishable_private"
                        + " jdbc:postgresql://private-pooler/private-db db-password private-db-user "
                        + EMAIL
                        + " "
                        + ID;
        var sql = new SQLException(privateMessage, "08001");
        var failure = new CannotCreateTransactionException(privateMessage, sql);
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenAnswer(
                        call -> {
                            try {
                                DatabaseDiagnostics.acquire(
                                        () -> {
                                            throw sql;
                                        });
                            } catch (SQLException ignored) {
                                throw failure; // Simulates the transaction proxy failing before
                                // method entry.
                            }
                            throw new AssertionError("Expected acquisition failure");
                        });
        when(users.getById(ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .hasNoCause()
                .extracting("code")
                .isEqualTo("ACCOUNT_PROVISIONING_FAILED");
        String log = output.getAll();
        assertThat(log)
                .contains(
                        "operation=account.create parentOperation=none attempt=1 event=failure connectionAcquired=false",
                        "operation=account.lookup parentOperation=none attempt=2 event=failure connectionAcquired=unknown",
                        "exception=CannotCreateTransactionException sqlException=SQLException sqlState=08001");
        assertThat(ID_PATTERN.matcher(log).results().map(match -> match.group(1)).distinct())
                .hasSize(1);
        assertThat(log)
                .doesNotContain(
                        PASSWORD,
                        "access-never-log",
                        "refresh-never-log",
                        "sb_publishable_private",
                        "jdbc:postgresql:",
                        "private-pooler",
                        "private-db",
                        "db-password",
                        "private-db-user",
                        EMAIL,
                        ID.toString(),
                        "Jane",
                        "Doe",
                        "Caused by:");
        verify(auth).signup(REQUEST);
        verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        verify(users).getById(ID);
        verifyNoMoreInteractions(users, auth);
    }

    @Test
    void successfulRetryLogsLookupAndSecondCreateWithoutChangingCallOrder(CapturedOutput output) {
        when(auth.signup(REQUEST)).thenReturn(identity());
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe"))
                .thenThrow(new CannotCreateTransactionException("do-not-log"))
                .thenReturn(account());
        when(users.getById(ID)).thenThrow(new AccountNotFoundException());
        assertThat(service.signup(REQUEST).user().id()).isEqualTo(ID);
        var order = inOrder(auth, users);
        order.verify(auth).signup(REQUEST);
        order.verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        order.verify(users).getById(ID);
        order.verify(users).createStandardAccount(ID, EMAIL, "Jane", "Doe");
        String log = output.getAll();
        assertThat(log)
                .contains(
                        "operation=account.create parentOperation=none attempt=1 event=failure",
                        "operation=account.lookup parentOperation=none attempt=2 event=success",
                        "operation=account.create parentOperation=none attempt=2 event=success")
                .doesNotContain("do-not-log");
        assertThat(ID_PATTERN.matcher(log).results().map(match -> match.group(1)).distinct())
                .hasSize(1);
    }

    @Test
    void separateSignupsGetDifferentServerIdsAndAuthFailuresDoNotTouchDatabase(
            CapturedOutput output) {
        when(auth.signup(REQUEST)).thenReturn(identity());
        when(users.createStandardAccount(ID, EMAIL, "Jane", "Doe")).thenReturn(account());
        service.signup(REQUEST);
        service.signup(REQUEST);
        assertThat(
                        ID_PATTERN
                                .matcher(output.getAll())
                                .results()
                                .map(match -> match.group(1))
                                .distinct())
                .hasSize(2);
        reset(auth, users);
        when(auth.signup(REQUEST)).thenThrow(SignupFailure.unavailable());
        assertThatThrownBy(() -> service.signup(REQUEST))
                .isInstanceOf(SignupFailure.class)
                .extracting("code")
                .isEqualTo("AUTH_UNAVAILABLE");
        verifyNoInteractions(users);
    }
}
