package edu.gatech.sip6321.diagnostics;

import static edu.gatech.sip6321.diagnostics.DatabaseDiagnostics.Operation.ACCOUNT_CREATE;
import static edu.gatech.sip6321.diagnostics.DatabaseDiagnostics.Operation.ACCOUNT_LOOKUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseDiagnosticsTest {
    private static final String PRIVATE =
            "password=private apiKey=private access_token=private"
                    + " refresh_token=private jdbc:postgresql://private-host/private-db"
                    + " student@example.test b6034174-d377-4ad4-b96a-6777660143e6 SELECT secret_parameter";
    private static final Pattern ID = Pattern.compile("correlationId=([0-9a-f-]{36})");

    @Test
    void acquisitionFailureLogsSqlStateTimingAndPreservesOriginalException(CapturedOutput output) {
        var failure = new SQLException(PRIVATE, "28P01");
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.acquire(
                                        () -> {
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertThat(output.getAll())
                .contains(
                        "operation=connection.acquire",
                        "event=start",
                        "event=failure connectionAcquired=false",
                        "exception=SQLException",
                        "sqlException=SQLException sqlState=28P01")
                .doesNotContain(PRIVATE);
        assertThat(output.getAll()).containsPattern("elapsedMs=\\d+");
        assertThat(output.getAll()).containsPattern(ID);
    }

    @Test
    void acquiredConnectionIsReturnedUnchangedAndSharedWithOperation(CapturedOutput output) {
        Connection connection = mock(Connection.class);
        Connection returned =
                DatabaseDiagnostics.signup(
                        () ->
                                DatabaseDiagnostics.operation(
                                        ACCOUNT_CREATE, 1, () -> acquire(connection)));
        assertThat(returned).isSameAs(connection);
        assertThat(output.getAll())
                .contains(
                        "parentOperation=account.create attempt=1",
                        "operation=account.create parentOperation=none attempt=1 event=success connectionAcquired=true");
        assertThat(ID.matcher(output.getAll()).results().map(match -> match.group(1)).distinct())
                .hasSize(1);
    }

    @Test
    void initializationFailureAndSqlExceptionChainAreObservable(CapturedOutput output) {
        var sql = new SQLTransientConnectionException(PRIVATE);
        sql.setNextException(new SQLException(PRIVATE, "08001"));
        var failure = new CannotCreateTransactionException(PRIVATE, sql);
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.signup(
                                        () ->
                                                DatabaseDiagnostics.operation(
                                                        ACCOUNT_CREATE,
                                                        1,
                                                        () -> {
                                                            throw failure;
                                                        })))
                .isSameAs(failure);
        assertThat(output.getAll())
                .contains(
                        "event=failure connectionAcquired=unknown",
                        "exception=CannotCreateTransactionException",
                        "sqlException=SQLException sqlState=08001");
        noPrivateData(output);
    }

    @Test
    void failuresAfterAcquisitionAreDistinguishable(CapturedOutput output) {
        var failure = new IllegalStateException(PRIVATE, new SQLException(PRIVATE, "23503"));
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.operation(
                                        ACCOUNT_CREATE,
                                        1,
                                        () -> {
                                            acquire(mock(Connection.class));
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertThat(output.getAll())
                .contains(
                        "operation=account.create parentOperation=none attempt=1"
                                + " event=failure connectionAcquired=true",
                        "sqlState=23503");
        noPrivateData(output);
    }

    @Test
    void transactionOriginalExceptionIsInspectedWithoutLoggingItsMessage(CapturedOutput output) {
        var failure = new TransactionSystemException(PRIVATE);
        failure.initApplicationException(new SQLException(PRIVATE, "40001"));
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.operation(
                                        ACCOUNT_LOOKUP,
                                        2,
                                        () -> {
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertThat(output.getAll())
                .contains("exception=TransactionSystemException", "sqlState=40001");
        noPrivateData(output);
    }

    @Test
    void cyclicCausesAndUnsafeSqlStateAreBoundedAndSanitized(CapturedOutput output) {
        var first = new SQLException(PRIVATE, "28P01\n" + PRIVATE);
        var second = new SQLException(PRIVATE);
        first.initCause(second);
        second.initCause(first);
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.acquire(
                                        () -> {
                                            throw first;
                                        }))
                .isSameAs(first);
        assertThat(output.getAll()).contains("sqlState=unknown");
        noPrivateData(output);
    }

    @Test
    void scopesResetAcquisitionStateAndDoNotLeakAcrossReusedThreads(CapturedOutput output)
            throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(
                            () ->
                                    DatabaseDiagnostics.signup(
                                            () -> {
                                                DatabaseDiagnostics.operation(
                                                        ACCOUNT_CREATE,
                                                        1,
                                                        () -> acquire(mock(Connection.class)));
                                                return DatabaseDiagnostics.operation(
                                                        ACCOUNT_LOOKUP, 2, () -> true);
                                            }))
                    .get();
            worker.submit(
                            () ->
                                    DatabaseDiagnostics.signup(
                                            () ->
                                                    DatabaseDiagnostics.operation(
                                                            ACCOUNT_CREATE, 1, () -> true)))
                    .get();
        }
        assertThat(output.getAll())
                .contains(
                        "operation=account.lookup parentOperation=none attempt=2"
                                + " event=success connectionAcquired=unknown");
        assertThat(ID.matcher(output.getAll()).results().map(match -> match.group(1)).distinct())
                .hasSize(2);
    }

    @Test
    void failedScopeDoesNotLeakCorrelationIntoNextCall(CapturedOutput output) {
        assertThatThrownBy(
                        () ->
                                DatabaseDiagnostics.signup(
                                        () ->
                                                DatabaseDiagnostics.operation(
                                                        ACCOUNT_CREATE,
                                                        1,
                                                        () -> {
                                                            throw new IllegalStateException(
                                                                    PRIVATE);
                                                        })))
                .isInstanceOf(IllegalStateException.class);
        DatabaseDiagnostics.operation(ACCOUNT_LOOKUP, 1, () -> true);
        assertThat(ID.matcher(output.getAll()).results().map(match -> match.group(1)).distinct())
                .hasSize(2);
        noPrivateData(output);
    }

    private static Connection acquire(Connection connection) {
        try {
            return DatabaseDiagnostics.acquire(() -> connection);
        } catch (SQLException exception) {
            throw new AssertionError("Unexpected test acquisition failure");
        }
    }

    private void noPrivateData(CapturedOutput output) {
        assertThat(output.getAll())
                .doesNotContain(
                        "password=private",
                        "apiKey=private",
                        "access_token=private",
                        "refresh_token=private",
                        "jdbc:postgresql:",
                        "private-host",
                        "private-db",
                        "student@example.test",
                        "b6034174-d377-4ad4-b96a-6777660143e6",
                        "secret_parameter",
                        "at edu.gatech",
                        "Caused by:");
    }
}
