package edu.gatech.sip6321.diagnostics;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Retains Hikari's lazy construction, lifecycle, settings, connections and exception behavior. */
public final class DiagnosticHikariDataSource extends HikariDataSource {
    @Override
    public Connection getConnection() throws SQLException {
        return DatabaseDiagnostics.acquire(super::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DatabaseDiagnostics.acquire(() -> super.getConnection(username, password));
    }
}
