package edu.gatech.sip6321;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.gatech.sip6321.config.DatabaseConnectivityCheck;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseConnectivityCheckTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
    private LogLevel hikariLevel;
    private LogLevel postgresLevel;

    @BeforeEach
    void prepare() throws Exception {
        hikariLevel = configuredLevel("com.zaxxer.hikari");
        postgresLevel = configuredLevel("org.postgresql");
        logs.start();
        root.addAppender(logs);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
    }

    @AfterEach
    void restoreLogging() {
        root.detachAppender(logs);
        logs.stop();
        logging.setLogLevel("com.zaxxer.hikari", hikariLevel);
        logging.setLogLevel("org.postgresql", postgresLevel);
    }

    private LogLevel configuredLevel(String name) {
        var configuration = logging.getLoggerConfiguration(name);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    @ParameterizedTest
    @CsvSource({
        "prod,true,true",
        "render,true,true",
        "prod,false,false",
        "render,false,false",
        "local,true,false",
        "database,true,false",
        "prod,unset,false",
        "prod,yes,false"
    })
    void requiresDeploymentProfileAndExplicitFlag(String profile, String flag, boolean enabled) {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(DatabaseConnectivityCheck.class)
                        .withBean(DataSource.class, () -> dataSource)
                        .withPropertyValues("spring.profiles.active=" + profile);
        if (!flag.equals("unset")) {
            runner = runner.withPropertyValues("SIP_DB_CONNECTIVITY_CHECK=" + flag);
        }
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DatabaseConnectivityCheck.class))
                            .hasSize(enabled ? 1 : 0);
                    verifyNoInteractions(dataSource);
                });
    }

    @Test
    void successfulQueryHasTimeoutAndClosesAllResources() throws Exception {
        new DatabaseConnectivityCheck(dataSource).run(null);
        assertMarker("PASS");
        verify(statement).setQueryTimeout(5);
        verify(statement).executeQuery("SELECT 1");
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void unexpectedResultFailsAndClosesResources() throws Exception {
        when(result.getInt(1)).thenReturn(0);
        new DatabaseConnectivityCheck(dataSource).run(null);
        assertMarker("FAIL");
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void queryFailureIsSanitizedAndClosesResources() throws Exception {
        when(statement.executeQuery("SELECT 1")).thenThrow(new SQLException("private SQL error"));
        new DatabaseConnectivityCheck(dataSource).run(null);
        assertMarker("FAIL");
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void connectionFailureSuppressesPoolAndDriverDiagnostics() throws Exception {
        when(dataSource.getConnection())
                .thenAnswer(
                        invocation -> {
                            LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool")
                                    .error("private pool details");
                            LoggerFactory.getLogger("org.postgresql.Driver")
                                    .error("private driver details");
                            throw new SQLException("private connection string and password");
                        });
        new DatabaseConnectivityCheck(dataSource).run(null);
        assertMarker("FAIL");
        verifyNoInteractions(connection, statement, result);
    }

    @Test
    void stalledConnectionCannotBlockStartupIndefinitely() throws Exception {
        var cancelled = new CountDownLatch(1);
        when(dataSource.getConnection())
                .thenAnswer(
                        invocation -> {
                            try {
                                new CountDownLatch(1).await();
                                return connection;
                            } finally {
                                cancelled.countDown();
                            }
                        });
        assertTimeoutPreemptively(
                Duration.ofSeconds(18), () -> new DatabaseConnectivityCheck(dataSource).run(null));
        assertMarker("FAIL");
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private void assertMarker(String status) {
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Database connectivity check: " + status);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
