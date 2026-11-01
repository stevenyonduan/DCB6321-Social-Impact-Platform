package edu.gatech.sip6321.auth;

import static edu.gatech.sip6321.diagnostics.DatabaseDiagnostics.Operation.ACCOUNT_LOOKUP;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserService;
import edu.gatech.sip6321.diagnostics.DatabaseDiagnostics;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** Auth establishes identity; the application lookup is read-only and never provisions accounts. */
public class LoginService {
    private final SupabaseAuthClient auth;
    private final UserService users;

    public LoginService(SupabaseAuthClient auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    public SignupResponse login(LoginRequest request) {
        var identity = auth.login(request);
        User user;
        try {
            user =
                    DatabaseDiagnostics.operation(
                            ACCOUNT_LOOKUP, 1, () -> users.getById(identity.id()));
        } catch (AccountNotFoundException exception) {
            throw LoginFailure.provisioning();
        } catch (DataAccessException
                | CannotCreateTransactionException
                | TransactionSystemException exception) {
            throw LoginFailure.database();
        }
        if (identity.session().expiresAt() <= Instant.now().getEpochSecond()) {
            throw LoginFailure.fromAuth(SignupFailure.upstream());
        }
        return SignupResponse.of(user, identity.session());
    }
}
