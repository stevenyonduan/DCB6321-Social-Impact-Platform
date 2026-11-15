package edu.gatech.sip6321;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;

/** Executes the complete transactional script, including PostgreSQL dollar-quoted DO blocks. */
public final class OrganizationManagementMigrations {
    private OrganizationManagementMigrations() {}

    public static void apply(DataSource source) {
        apply(source, "");
    }

    public static void apply(DataSource source, String approvedMappingSql) {
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    Files.readString(Path.of("db/migrations/V004__organization_management.sql"))
                            .replace(
                                    "-- INSERT INTO approved_organization_roles VALUES",
                                    approvedMappingSql
                                            + "\n-- INSERT INTO approved_organization_roles VALUES"));
        } catch (Exception e) {
            throw new IllegalStateException("Isolated V004 migration failed", e);
        }
    }
}
