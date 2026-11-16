package edu.gatech.sip6321.registration;

/** Fixed, public errors. Driver messages and request values never become response messages. */
public final class RegistrationFailure extends RuntimeException {
    private final int status;
    private final String code;

    private RegistrationFailure(int status, String code, String message) {
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

    public static RegistrationFailure invalid() {
        return new RegistrationFailure(
                400,
                "INVALID_REQUEST",
                "Check the event ID and pagination parameters. No request body is accepted.");
    }

    public static RegistrationFailure missingEvent() {
        return new RegistrationFailure(404, "EVENT_NOT_FOUND", "Event not found.");
    }

    public static RegistrationFailure external() {
        return new RegistrationFailure(
                400,
                "INTERNAL_REGISTRATION_REQUIRED",
                "This event does not accept internal registration.");
    }

    public static RegistrationFailure started() {
        return new RegistrationFailure(
                400,
                "EVENT_ALREADY_STARTED",
                "Registration changes require an event that has not started.");
    }

    public static RegistrationFailure duplicate() {
        return new RegistrationFailure(
                409, "ALREADY_REGISTERED", "You are already registered for this event.");
    }

    public static RegistrationFailure missingRegistration() {
        return new RegistrationFailure(
                404, "REGISTRATION_NOT_FOUND", "Your registration was not found.");
    }
}
