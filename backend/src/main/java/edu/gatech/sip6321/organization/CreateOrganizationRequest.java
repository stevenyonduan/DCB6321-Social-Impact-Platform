package edu.gatech.sip6321.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;

public record CreateOrganizationRequest(String name, String description, String website) {
    public CreateOrganizationRequest {
        OrganizationPatch.validate("name", name);
        OrganizationPatch.validate("description", description);
        OrganizationPatch.validate("website", website);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CreateOrganizationRequest fromJson(JsonNode node) {
        var patch = OrganizationPatch.fromJson(node);
        return new CreateOrganizationRequest(
                patch.value("name"), patch.value("description"), patch.value("website"));
    }

    @Override
    public String toString() {
        return "CreateOrganizationRequest[redacted]";
    }
}
