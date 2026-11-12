package edu.gatech.sip6321.opportunity;

public final class OpportunityFailure extends RuntimeException {
    private final int status;
    private final String code;

    private OpportunityFailure(int status, String code, String message) {
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

    public static OpportunityFailure invalid() {
        return new OpportunityFailure(
                400, "INVALID_REQUEST", "Check the opportunity fields and pagination parameters.");
    }

    public static OpportunityFailure forbidden() {
        return new OpportunityFailure(403, "FORBIDDEN", "Opportunity publishing is not permitted.");
    }

    public static OpportunityFailure missing() {
        return new OpportunityFailure(404, "ORGANIZATION_NOT_FOUND", "Organization not found.");
    }
}
