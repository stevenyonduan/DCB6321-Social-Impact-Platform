package edu.gatech.sip6321.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findUserById(UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
