package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserRole;
import java.util.UUID;

public record SignupResponse(Account user, SupabaseAuthClient.Session session) {
    public static SignupResponse of(User user, SupabaseAuthClient.Session session) {
        return new SignupResponse(
                new Account(
                        user.getId(),
                        user.getEmail(),
                        user.getRole(),
                        user.getFirstName(),
                        user.getLastName()),
                session);
    }

    public record Account(
            UUID id, String email, UserRole role, String firstName, String lastName) {}

    @Override
    public String toString() {
        return "SignupResponse[redacted]";
    }
}
