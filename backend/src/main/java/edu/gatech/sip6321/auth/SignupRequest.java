package edu.gatech.sip6321.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 1024) String password,
        @NotBlank String firstName,
        @NotBlank String lastName) {
    private static final Set<String> FIELDS = Set.of("email", "password", "firstName", "lastName");

    // Strict, endpoint-local JSON policy: no privileged/unknown fields or scalar coercion.
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SignupRequest fromJson(JsonNode node) {
        if (!node.isObject() || !FIELDS.equals(Set.copyOf(node.propertyNames()))) {
            throw SignupFailure.invalid();
        }
        for (String field : FIELDS) {
            if (!node.path(field).isString() || !validUnicode(node.path(field).asString())) {
                throw SignupFailure.invalid();
            }
        }
        String email = node.path("email").asString();
        String first = node.path("firstName").asString();
        String last = node.path("lastName").asString();
        if (!email.equals(email.strip())
                || first.codePointCount(0, first.length()) > 100
                || last.codePointCount(0, last.length()) > 100) {
            throw SignupFailure.invalid();
        }
        return new SignupRequest(email, node.path("password").asString(), first, last);
    }

    static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) {
                return false;
            }
            if (Character.isHighSurrogate(c)
                    && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "SignupRequest[redacted]";
    }
}
