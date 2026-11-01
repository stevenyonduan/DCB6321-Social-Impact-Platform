package edu.gatech.sip6321.auth;

/** Safe application errors deliberately retain no upstream/driver exception or rejected value. */
public final class SignupFailure extends RuntimeException {
    private final int status;
    private final String code;

    public SignupFailure(int status, String code, String message) {
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

    public static SignupFailure invalid() {
        return new SignupFailure(400, "INVALID_REQUEST", "Check the signup fields and try again.");
    }

    public static SignupFailure conflict() {
        return new SignupFailure(409, "SIGNUP_CONFLICT", "Registration could not be completed.");
    }

    public static SignupFailure upstream() {
        return new SignupFailure(
                502, "AUTH_RESPONSE_INVALID", "Registration could not be completed.");
    }

    public static SignupFailure unavailable() {
        return new SignupFailure(
                503, "AUTH_UNAVAILABLE", "Registration is temporarily unavailable.");
    }

    public static SignupFailure provisioning() {
        return new SignupFailure(
                503,
                "ACCOUNT_PROVISIONING_FAILED",
                "Registration could not be completed. Contact support before registering again.");
    }
}
