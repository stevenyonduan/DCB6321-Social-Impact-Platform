package edu.gatech.sip6321.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findUserById(UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByOrganizationIdOrderById(UUID organizationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update User u set u.organizationId = :organizationId, u.organizationRole = :role where u.id = :id and u.organizationId is null and u.organizationRole is null")
    int claimOrganization(UUID id, UUID organizationId, OrganizationRole role);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update User u set u.organizationId = null, u.organizationRole = null where u.id = :id and u.organizationId = :organizationId and u.organizationRole = edu.gatech.sip6321.account.OrganizationRole.ADMIN")
    int removeAdmin(UUID id, UUID organizationId);
}
