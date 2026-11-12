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
        String location) {
    private static final Instant MIN_TIMESTAMP = Instant.parse("-4713-11-24T00:00:00Z");
    private static final Instant END_TIMESTAMP = Instant.parse("+294277-01-01T00:00:00Z");
    private static final Set<String> FIELDS =
            Set.of("title", "description", "imageUrl", "eventStart", "eventEnd", "location");

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
                    value(node, "location"));
        } catch (DateTimeException exception) {
            throw OpportunityFailure.invalid();
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
