package edu.gatech.sip6321.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Validated, presence-aware personal fields only. Never accepts account identity or permissions.
 */
public final class ProfilePatch {
    private static final Set<String> FIELDS = Set.of("firstName", "lastName", "bio");
    private final Map<String, String> values;

    private ProfilePatch(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProfilePatch fromJson(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) throw invalid();
        var values = new HashMap<String, String>();
        for (String field : node.propertyNames()) {
            if (!FIELDS.contains(field)) throw invalid();
            var value = node.get(field);
            if (!value.isNull() && !value.isString()) throw invalid();
            String text = value.isNull() ? null : value.asString();
            if (!"bio".equals(field)
                    && (text == null
                            || text.codePoints()
                                    .allMatch(
                                            c ->
                                                    Character.isWhitespace(c)
                                                            || Character.isSpaceChar(c))
                            || text.codePointCount(0, text.length()) > 100)) throw invalid();
            // PostgreSQL text cannot store NUL or malformed Unicode. Preserve all valid values.
            if (text != null)
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
                    if (Character.isHighSurrogate(c)
                            && (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))))
                        throw invalid();
                }
            values.put(field, text);
        }
        return new ProfilePatch(values);
    }

    boolean contains(String field) {
        return values.containsKey(field);
    }

    String value(String field) {
        return values.get(field);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid personal profile update.");
    }

    @Override
    public String toString() {
        return "ProfilePatch[redacted]";
    }
}
