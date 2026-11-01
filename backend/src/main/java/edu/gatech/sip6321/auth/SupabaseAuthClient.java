package edu.gatech.sip6321.auth;

import java.util.UUID;

public interface SupabaseAuthClient {
    VerifiedSignup signup(SignupRequest request);

    VerifiedLogin login(LoginRequest request);

    /** UUID returned only after Supabase authenticates the access token. */
    UUID verifyAccessToken(String accessToken);

    /** Constructed only after authenticated user lookup verifies the session identity. */
    record VerifiedLogin(UUID id, String email, Session session) {
        @Override
        public String toString() {
            return "VerifiedLogin[redacted]";
        }
    }

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
