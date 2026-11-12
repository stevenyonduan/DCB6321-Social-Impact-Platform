package edu.gatech.sip6321.opportunity;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record PublishOpportunityRequest(
        String title,
        String description,
        String imageUrl,
        Instant eventStart,
        Instant eventEnd,
        String location,
        RegistrationType registrationType,
        String registrationUrl) {
    private static final Instant MIN_TIMESTAMP = Instant.parse("-4713-11-24T00:00:00Z");
    private static final Instant END_TIMESTAMP = Instant.parse("+294277-01-01T00:00:00Z");
    private static final Set<String> FIELDS =
            Set.of(
                    "title",
                    "description",
                    "imageUrl",
                    "eventStart",
                    "eventEnd",
                    "location",
                    "registrationType",
                    "registrationUrl");

    public PublishOpportunityRequest {
        text(title, true, true);
        text(description, true, false);
        text(location, true, true);
        text(imageUrl, false, true);
        if (imageUrl != null) {
            try {
                URI uri = URI.create(imageUrl);
                if (!("https".equalsIgnoreCase(uri.getScheme())
                                || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw OpportunityFailure.invalid();
            }
        }
        if (registrationType == null) throw OpportunityFailure.invalidRegistration();
        if (registrationType == RegistrationType.INTERNAL) {
            if (registrationUrl != null) throw OpportunityFailure.invalidRegistration();
        } else {
            registrationUrl(registrationUrl);
        }
        eventStart = timestamp(eventStart);
        eventEnd = timestamp(eventEnd);
        if (!eventEnd.isAfter(eventStart)) throw OpportunityFailure.invalid();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PublishOpportunityRequest fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw OpportunityFailure.invalid();
        for (String field : node.propertyNames())
            if (!FIELDS.contains(field)) throw OpportunityFailure.invalid();
        try {
            return new PublishOpportunityRequest(
                    value(node, "title"),
                    value(node, "description"),
                    value(node, "imageUrl"),
                    parse(value(node, "eventStart")),
                    parse(value(node, "eventEnd")),
                    value(node, "location"),
                    registrationType(node),
                    value(node, "registrationUrl"));
        } catch (DateTimeException exception) {
            throw OpportunityFailure.invalid();
        }
    }

    // Preserve the original Java construction contract with the same omission default as JSON/SQL.
    public PublishOpportunityRequest(
            String title,
            String description,
            String imageUrl,
            Instant eventStart,
            Instant eventEnd,
            String location) {
        this(
                title,
                description,
                imageUrl,
                eventStart,
                eventEnd,
                location,
                RegistrationType.INTERNAL,
                null);
    }

    private static RegistrationType registrationType(JsonNode node) {
        if (!node.has("registrationType")) return RegistrationType.INTERNAL;
        try {
            String value = value(node, "registrationType");
            if (value == null) throw OpportunityFailure.invalidRegistration();
            return RegistrationType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw OpportunityFailure.invalidRegistration();
        }
    }

    private static void registrationUrl(String value) {
        try {
            text(value, true, false);
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawAuthority().endsWith(":")
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                throw OpportunityFailure.invalidRegistration();
            // Also reject percent-encoded controls/bidi formatting in decoded URI components.
            for (String part :
                    new String[] {
                        value, uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment()
                    }) {
                if (part != null
                        && part.codePoints()
                                .anyMatch(
                                        c ->
                                                Character.isISOControl(c)
                                                        || Character.getType(c) == Character.FORMAT
                                                        || Character.getType(c)
                                                                == Character.LINE_SEPARATOR
                                                        || Character.getType(c)
                                                                == Character.PARAGRAPH_SEPARATOR
                                                        || c == 0xfffd
                                                        || (c >= 0xfdd0 && c <= 0xfdef)
                                                        || (c & 0xfffe) == 0xfffe))
                    throw OpportunityFailure.invalidRegistration();
            }
        } catch (IllegalArgumentException | OpportunityFailure exception) {
            throw OpportunityFailure.invalidRegistration();
        }
    }

    private static String value(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw OpportunityFailure.invalid();
        return value.asString();
    }

    private static Instant parse(String value) {
        if (value == null) throw OpportunityFailure.invalid();
        return OffsetDateTime.parse(value).toInstant();
    }

    private static Instant timestamp(Instant value) {
        // PostgreSQL's finite timestamp range; normalize to its microsecond precision before
        // comparing.
        if (value == null || value.isBefore(MIN_TIMESTAMP) || !value.isBefore(END_TIMESTAMP))
            throw OpportunityFailure.invalid();
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static void text(String value, boolean required, boolean limited) {
        if (required && (value == null || value.isBlank())) throw OpportunityFailure.invalid();
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw OpportunityFailure.invalid();
            if (Character.isHighSurrogate(c)
                    && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))))
                throw OpportunityFailure.invalid();
        }
        if (limited && value.codePointCount(0, value.length()) > 255)
            throw OpportunityFailure.invalid();
    }

    @Override
    public String toString() {
        return "PublishOpportunityRequest[redacted]";
    }
}
