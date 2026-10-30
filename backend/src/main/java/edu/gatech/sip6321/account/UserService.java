package edu.gatech.sip6321.account;

import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal service. Callers must supply a trusted Auth identity, never unverified request data. */
@Service
@Profile({"database", "prod", "render"})
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public User createStandardAccount(
            UUID authUserId, String email, String firstName, String lastName) {
        requireId(authUserId);
        requireText(email, 255, "email");
        requireText(firstName, 100, "firstName");
        requireText(lastName, 100, "lastName");
        if (users.existsById(authUserId) || users.existsByEmail(email)) {
            throw new DuplicateAccountException();
        }
        try {
            return users.saveAndFlush(User.standardAccount(authUserId, email, firstName, lastName));
        } catch (DataIntegrityViolationException exception) {
            // Prechecks cannot exclude concurrent inserts. Only classify the two known uniques.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && ("users_pkey".equals(violation.getConstraintName())
                                || "users_email_key".equals(violation.getConstraintName()))) {
                    // Do not retain the driver exception: it can contain email/profile values.
                    throw new DuplicateAccountException();
                }
            }
            throw exception;
        }
    }

    public User getById(UUID id) {
        requireId(id);
        return users.findById(id).orElseThrow(AccountNotFoundException::new);
    }

    public User getByEmail(String email) {
        requireText(email, 255, "email");
        return users.findByEmail(email).orElseThrow(AccountNotFoundException::new);
    }

    public boolean existsById(UUID id) {
        requireId(id);
        return users.existsById(id);
    }

    public boolean existsByEmail(String email) {
        requireText(email, 255, "email");
        return users.existsByEmail(email);
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Auth user UUID is required");
        }
    }

    private static void requireText(String value, int limit, String field) {
        // Preserve exact values: normalization and blank-text policy are separate decisions.
        if (value == null || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException(
                    field + " is required and must fit its column limit");
        }
    }
}
