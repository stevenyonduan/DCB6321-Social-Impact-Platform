package edu.gatech.sip6321.auth;

import static org.mockito.Mockito.mock;

import edu.gatech.sip6321.account.User;
import edu.gatech.sip6321.account.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.mockito.Answers;

final class SignupFixtures {
    static final UUID ID = UUID.fromString("b6034174-d377-4ad4-b96a-6777660143e6");
    static final String EMAIL = "student@example.test";
    static final String PASSWORD = "test-password-never-log";
    static final SignupRequest REQUEST = new SignupRequest(EMAIL, PASSWORD, "Jane", "Doe");

    static SupabaseAuthClient.VerifiedSignup identity() {
        return new SupabaseAuthClient.VerifiedSignup(
                ID,
                EMAIL,
                new SupabaseAuthClient.Session(
                        "access-never-log",
                        "refresh-never-log",
                        "bearer",
                        3600,
                        Instant.now().getEpochSecond() + 3600));
    }

    static User account() {
        return mock(
                User.class,
                invocation ->
                        switch (invocation.getMethod().getName()) {
                            case "getId" -> ID;
                            case "getEmail" -> EMAIL;
                            case "getRole" -> UserRole.STANDARD;
                            case "getFirstName" -> "Jane";
                            case "getLastName" -> "Doe";
                            default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                        });
    }
}
