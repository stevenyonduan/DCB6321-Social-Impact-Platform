package edu.gatech.sip6321.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DiagnosticHikariDataSourceTest {
    @Test
    void constructionStaysLazyAndDoesNotChangeHikariDefaults() {
        try (var pool = new DiagnosticHikariDataSource()) {
            assertThat(pool.getHikariPoolMXBean()).isNull();
            assertThat(pool.isRunning()).isFalse();
            assertThat(pool.getInitializationFailTimeout()).isEqualTo(1);
        }
    }

    @Test
    void actualHikariAcquisitionFailureIsLoggedWithoutInitializingAConnection(
            CapturedOutput output) {
        var pool = new DiagnosticHikariDataSource();
        pool.close();
        assertThatThrownBy(pool::getConnection).isInstanceOf(SQLException.class);
        assertThat(output.getAll())
                .contains(
                        "operation=connection.acquire",
                        "event=failure connectionAcquired=false",
                        "exception=SQLException",
                        "sqlState=unknown");
    }

    @Test
    void credentialOverloadPreservesHikariBehaviorAndDoesNotLogCredentials(CapturedOutput output) {
        try (var pool = new DiagnosticHikariDataSource()) {
            assertThatThrownBy(() -> pool.getConnection("private-db-user", "private-db-password"))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }
        assertThat(output.getAll())
                .contains("exception=SQLFeatureNotSupportedException")
                .doesNotContain("private-db-user", "private-db-password");
    }
}
