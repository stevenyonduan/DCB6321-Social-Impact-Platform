package edu.gatech.sip6321.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record AddAdminRequest(String email) {
    public AddAdminRequest {
        OrganizationPatch.validate("email", email);
        if (email == null
                || email.length() > 255
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || email.chars().anyMatch(Character::isISOControl))
            throw OrganizationFailure.invalid();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AddAdminRequest fromJson(JsonNode node) {
        if (node == null
                || !node.isObject()
                || !node.propertyNames().equals(Set.of("email"))
                || !node.get("email").isString()) throw OrganizationFailure.invalid();
        return new AddAdminRequest(node.get("email").asString());
    }

    @Override
    public String toString() {
        return "AddAdminRequest[redacted]";
    }
}
