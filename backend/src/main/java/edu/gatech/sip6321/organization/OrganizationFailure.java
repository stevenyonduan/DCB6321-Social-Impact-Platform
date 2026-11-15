package edu.gatech.sip6321.organization;

/** Fixed public messages only; no rejected values or driver causes retained. */
public final class OrganizationFailure extends RuntimeException {
    private final int status;
    private final String code;

    private OrganizationFailure(int status, String code, String message) {
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

    public static OrganizationFailure conflict() {
        return new OrganizationFailure(
                409, "MEMBERSHIP_CONFLICT", "Organization membership could not be assigned.");
    }

    public static OrganizationFailure memberMissing() {
        return new OrganizationFailure(404, "ADMIN_NOT_FOUND", "Organization admin not found.");
    }

    public static OrganizationFailure rateLimited() {
        return new OrganizationFailure(
                429, "MANAGEMENT_RATE_LIMITED", "Too many management attempts. Try again later.");
    }

    public static OrganizationFailure invalid() {
        return new OrganizationFailure(
                400, "INVALID_REQUEST", "Check the organization fields and try again.");
    }

    public static OrganizationFailure forbidden() {
        return new OrganizationFailure(403, "FORBIDDEN", "Organization access is not permitted.");
    }

    public static OrganizationFailure missing() {
        return new OrganizationFailure(404, "ORGANIZATION_NOT_FOUND", "Organization not found.");
    }

    public static OrganizationFailure alreadyAssociated() {
        return new OrganizationFailure(
                409,
                "ORGANIZATION_ALREADY_ASSOCIATED",
                "This account is already linked to an organization.");
    }
}
