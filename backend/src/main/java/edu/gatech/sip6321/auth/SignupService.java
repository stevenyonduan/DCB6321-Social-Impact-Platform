package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.DuplicateAccountException;
import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserRole;
import edu.gatech.sip6321.account.UserService;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** No outer transaction: every UserService call must enter/leave its own proxied transaction. */
public class SignupService {
    private final SupabaseAuthClient auth;
    private final UserService users;

    public SignupService(SupabaseAuthClient auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    public SignupResponse signup(SignupRequest request) {
        var identity = auth.signup(request); // Exactly once, even when JDBC fails.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                User user = attempt == 0 ? null : matchingAccount(identity);
                if (user == null) {
                    user =
                            users.createStandardAccount(
                                    identity.id(),
                                    identity.email(),
                                    request.firstName(),
                                    request.lastName());
                }
                return response(user, identity);
            } catch (DuplicateAccountException exception) {
                try {
                    User existing = matchingAccount(identity);
                    if (existing != null) {
                        return response(existing, identity);
                    }
                    throw SignupFailure.conflict();
                } catch (DataAccessException
                        | CannotCreateTransactionException
                        | TransactionSystemException failure) {
                    throw SignupFailure.provisioning();
                }
            } catch (TransientDataAccessException
                    | DataAccessResourceFailureException
                    | CannotCreateTransactionException
                    | TransactionSystemException exception) {
                if (attempt == 1) {
                    throw SignupFailure.provisioning();
                }
            } catch (DataAccessException exception) {
                throw SignupFailure.provisioning();
            }
        }
        throw SignupFailure.provisioning();
    }

    private User matchingAccount(SupabaseAuthClient.VerifiedSignup identity) {
        try {
            User user = users.getById(identity.id());
            if (!user.getEmail().equals(identity.email()) || user.getRole() != UserRole.STANDARD) {
                throw SignupFailure.conflict();
            }
            return user;
        } catch (AccountNotFoundException exception) {
            return null;
        }
    }

    private SignupResponse response(User user, SupabaseAuthClient.VerifiedSignup identity) {
        if (identity.session().expiresAt() <= Instant.now().getEpochSecond()) {
            throw SignupFailure.provisioning();
        }
        return SignupResponse.of(user, identity.session());
    }
}
