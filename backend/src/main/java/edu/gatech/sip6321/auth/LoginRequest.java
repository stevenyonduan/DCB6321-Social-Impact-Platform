package edu.gatech.sip6321.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 1024) String password) {
    private static final Set<String> FIELDS = Set.of("email", "password");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LoginRequest fromJson(JsonNode node) {
        if (!node.isObject() || !FIELDS.equals(Set.copyOf(node.propertyNames()))) {
            throw LoginFailure.invalid();
        }
        for (String field : FIELDS) {
            if (!node.path(field).isString()
                    || !SignupRequest.validUnicode(node.path(field).asString())) {
                throw LoginFailure.invalid();
            }
        }
        String email = node.path("email").asString();
        if (!email.equals(email.strip())) {
            throw LoginFailure.invalid();
        }
        return new LoginRequest(email, node.path("password").asString());
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
