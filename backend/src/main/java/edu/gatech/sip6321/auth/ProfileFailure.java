package edu.gatech.sip6321.auth;

/** Public fixed messages only; no sensitive causes retained. */
public final class ProfileFailure extends RuntimeException {
    private final int status;
    private final String code;

    private ProfileFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ProfileFailure unauthorized() {
        return new ProfileFailure(401, "UNAUTHORIZED", "A valid Bearer access token is required.");
    }

    public static ProfileFailure provisioning() {
        return new ProfileFailure(
                503,
                "ACCOUNT_PROVISIONING_REQUIRED",
                "Account access is unavailable. Contact support.");
    }

    public static ProfileFailure database() {
        return new ProfileFailure(
                503, "ACCOUNT_UNAVAILABLE", "Account access is temporarily unavailable.");
    }

    static ProfileFailure fromAuth(SignupFailure failure) {
        return switch (failure.code()) {
            case "AUTH_UNAVAILABLE" ->
                    new ProfileFailure(
                            503, "AUTH_UNAVAILABLE", "Authentication is temporarily unavailable.");
            case "AUTH_RATE_LIMITED" ->
                    new ProfileFailure(429, "AUTH_RATE_LIMITED", "Please try again later.");
            default ->
                    new ProfileFailure(
                            502, "AUTH_RESPONSE_INVALID", "Authentication could not be completed.");
        };
    }
}
