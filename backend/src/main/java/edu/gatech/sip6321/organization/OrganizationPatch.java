package edu.gatech.sip6321.organization;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Presence-aware immutable patch. Null clears optional fields; omission preserves them. */
public final class OrganizationPatch {
    private static final Set<String> FIELDS = Set.of("name", "description", "website");
    private final Map<String, String> values;

    private OrganizationPatch(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static OrganizationPatch fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw OrganizationFailure.invalid();
        var values = new HashMap<String, String>();
        for (String field : node.propertyNames()) {
            if (!FIELDS.contains(field)) throw OrganizationFailure.invalid();
            var value = node.get(field);
            if (!value.isNull() && !value.isString()) throw OrganizationFailure.invalid();
            String text = value.isNull() ? null : value.asString();
            validate(field, text);
            values.put(field, text);
        }
        return new OrganizationPatch(values);
    }

    static void validate(String field, String value) {
        if ("name".equals(field) && (value == null || value.isBlank()))
            throw OrganizationFailure.invalid();
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw OrganizationFailure.invalid();
            if (Character.isHighSurrogate(c)
                    && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))))
                throw OrganizationFailure.invalid();
        }
        if (!"description".equals(field) && value.codePointCount(0, value.length()) > 255)
            throw OrganizationFailure.invalid();
        if ("website".equals(field)) {
            try {
                URI uri = URI.create(value);
                if (!("https".equalsIgnoreCase(uri.getScheme())
                                || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw OrganizationFailure.invalid();
            }
        }
    }

    boolean contains(String field) {
        return values.containsKey(field);
    }

    String value(String field) {
        return values.get(field);
    }

    @Override
    public String toString() {
        return "OrganizationPatch[redacted]";
    }
}
