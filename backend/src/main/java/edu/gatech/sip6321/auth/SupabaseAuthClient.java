package edu.gatech.sip6321.auth;

import java.util.UUID;

public interface SupabaseAuthClient {
    VerifiedSignup signup(SignupRequest request);

    record Session(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            long expiresAt) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }

    /**
     * Only constructed after checking the signup session against Supabase's authenticated user API.
     */
    record VerifiedSignup(UUID id, String email, Session session) {
        @Override
        public String toString() {
            return "VerifiedSignup[redacted]";
        }
    }
}
