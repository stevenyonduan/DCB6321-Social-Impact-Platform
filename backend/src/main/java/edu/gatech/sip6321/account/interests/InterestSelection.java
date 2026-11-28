package edu.gatech.sip6321.account.interests;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public record InterestSelection(Set<UUID> interestIds) {
    public InterestSelection {
        interestIds = Set.copyOf(interestIds);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static InterestSelection fromJson(JsonNode node) {
        if (node == null
                || !node.isObject()
                || !node.propertyNames().equals(Set.of("interestIds"))
                || !node.get("interestIds").isArray()) {
            throw new IllegalArgumentException("Expected an interestIds array.");
        }
        var ids =
                node.get("interestIds")
                        .valueStream()
                        .map(InterestSelection::parseId)
                        .collect(Collectors.toSet());
        return new InterestSelection(ids);
    }

    private static UUID parseId(JsonNode value) {
        if (!value.isString() || value.asString().length() != 36) {
            throw new IllegalArgumentException("Expected interest UUIDs.");
        }
        var text = value.asString();
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("Expected interest UUIDs.");
        }
        return id;
    }
}
