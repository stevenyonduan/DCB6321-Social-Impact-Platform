package edu.gatech.sip6321.config;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Temporary, opt-in diagnostic. Remove after deployment connectivity is verified. */
@Component
@Profile({"prod", "render"})
@ConditionalOnProperty(name = "SIP_DB_CONNECTIVITY_CHECK", havingValue = "true")
public class DatabaseConnectivityCheck implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseConnectivityCheck.class);
    private final DataSource dataSource;

    public DatabaseConnectivityCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        // Pool/driver diagnostics can contain connection details, including on background threads.
        // Keep them disabled for this process; removing the flag restores normal logging next
        // start.
        var logging = LoggingSystem.get(getClass().getClassLoader());
        logging.setLogLevel("com.zaxxer.hikari", LogLevel.OFF);
        logging.setLogLevel("org.postgresql", LogLevel.OFF);

        var check = new FutureTask<>(this::query);
        // A stalled JDBC operation must not keep startup or JVM shutdown waiting indefinitely.
        Thread.ofPlatform().daemon().name("database-connectivity-check").start(check);
        boolean passed = false;
        try {
            passed = check.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            // Do not log exceptions: driver messages may contain sensitive connection information.
        } finally {
            check.cancel(true);
        }
        LOGGER.info("Database connectivity check: {}", passed ? "PASS" : "FAIL");
    }

    private boolean query() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SELECT 1")) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (Exception exception) {
            return false;
        }
    }
}
