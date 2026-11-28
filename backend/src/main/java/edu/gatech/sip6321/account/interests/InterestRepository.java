package edu.gatech.sip6321.account.interests;

import edu.gatech.sip6321.organization.OrganizationFailure;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
class InterestRepository {
    // All SQL identifiers come from this closed set. Request values are bound parameters.
    enum Scope {
        USER("user_interests", "user_id", "users"),
        ORGANIZATION("organization_interests", "organization_id", "organizations");

        final String table;
        final String ownerColumn;
        final String parentTable;

        Scope(String table, String ownerColumn, String parentTable) {
            this.table = table;
            this.ownerColumn = ownerColumn;
            this.parentTable = parentTable;
        }
    }

    private final JdbcClient jdbc;

    InterestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<InterestOption> catalog() {
        return jdbc.sql("SELECT id, name FROM public.interests ORDER BY name, id")
                .query(InterestOption.class)
                .list();
    }

    List<InterestOption> selections(Scope scope, UUID ownerId) {
        return jdbc.sql(
                        """
                SELECT i.id, i.name
                FROM public.interests i
                JOIN public.%s s ON s.interest_id = i.id
                WHERE s.%s = :ownerId
                ORDER BY i.name, i.id
                """
                                .formatted(scope.table, scope.ownerColumn))
                .param("ownerId", ownerId)
                .query(InterestOption.class)
                .list();
    }

    void lockManager(UUID userId, UUID organizationId) {
        // Recheck the persisted association under a lock that conflicts with admin removal.
        boolean authorized =
                jdbc.sql(
                                """
                SELECT id FROM public.users
                WHERE id = :userId AND organization_id = :organizationId
                  AND organization_role IN ('OWNER', 'ADMIN')
                FOR SHARE
                """)
                        .param("userId", userId)
                        .param("organizationId", organizationId)
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!authorized) throw OrganizationFailure.forbidden();
    }

    boolean lockOwner(Scope scope, UUID ownerId) {
        // A parent row is available to serialize replacements even when selections are empty.
        return jdbc.sql(
                        "SELECT id FROM public.%s WHERE id = :ownerId FOR NO KEY UPDATE"
                                .formatted(scope.parentTable))
                .param("ownerId", ownerId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    boolean replace(Scope scope, UUID ownerId, Set<UUID> ids) {
        jdbc.sql(
                        "DELETE FROM public.%s WHERE %s = :ownerId"
                                .formatted(scope.table, scope.ownerColumn))
                .param("ownerId", ownerId)
                .update();
        if (ids.isEmpty()) return true;
        int inserted =
                jdbc.sql(
                                """
                INSERT INTO public.%s (%s, interest_id)
                            SELECT :ownerId, id FROM public.interests WHERE id = ANY(:ids)
                """
                                        .formatted(scope.table, scope.ownerColumn))
                        .param("ownerId", ownerId)
                        .param("ids", new SqlArrayValue("uuid", ids.toArray()))
                        .update();
        return inserted == ids.size();
    }
}
