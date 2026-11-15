package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.AccountNotFoundException;
import edu.gatech.sip6321.account.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

public class ProfileService {
    private final SupabaseAuthClient auth;
    private final UserService users;

    public ProfileService(SupabaseAuthClient auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    public ProfileResponse me(List<String> authorization) {
        var id = verifiedUserId(authorization);
        try {
            return ProfileResponse.of(users.getById(id));
        } catch (AccountNotFoundException exception) {
            throw ProfileFailure.provisioning();
        } catch (DataAccessException
                | CannotCreateTransactionException
                | TransactionSystemException exception) {
            throw ProfileFailure.database();
        }
    }

    public UUID verifiedUserId(List<String> authorization) {
        if (authorization == null || authorization.size() != 1) {
            throw ProfileFailure.unauthorized();
        }
        String header = authorization.getFirst();
        // RFC 6750 Bearer credentials; reject combined headers, extra credentials and controls.
        if (header == null
                || header.length() > 16391
                || !header.matches("(?i:Bearer) +[A-Za-z0-9._~+/-]+=*")) {
            throw ProfileFailure.unauthorized();
        }
        String token = header.substring(header.indexOf(' ') + 1).stripLeading();
        return auth.verifyAccessToken(token);
    }
}
