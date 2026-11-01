package edu.gatech.sip6321.auth;

/** Fixed public messages only; no provider/driver exceptions or rejected values retained. */
public final class LoginFailure extends RuntimeException {
    private final int status;
    private final String code;

    private LoginFailure(int status, String code, String message) {
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

    public static LoginFailure invalid() {
        return new LoginFailure(400, "INVALID_REQUEST", "Check the login fields and try again.");
    }

    public static LoginFailure credentials() {
        return new LoginFailure(401, "INVALID_CREDENTIALS", "Invalid email or password.");
    }

    public static LoginFailure provisioning() {
        return new LoginFailure(
                503,
                "ACCOUNT_PROVISIONING_REQUIRED",
                "Account access is unavailable. Contact support.");
    }

    public static LoginFailure database() {
        return new LoginFailure(
                503, "ACCOUNT_UNAVAILABLE", "Account access is temporarily unavailable.");
    }

    static LoginFailure fromAuth(SignupFailure failure) {
        return switch (failure.code()) {
            case "AUTH_UNAVAILABLE" ->
                    new LoginFailure(503, "AUTH_UNAVAILABLE", "Login is temporarily unavailable.");
            case "AUTH_RATE_LIMITED" ->
                    new LoginFailure(429, "AUTH_RATE_LIMITED", "Please try again later.");
            default ->
                    new LoginFailure(502, "AUTH_RESPONSE_INVALID", "Login could not be completed.");
        };
    }
}
