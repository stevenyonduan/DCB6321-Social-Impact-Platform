package edu.gatech.sip6321.social;

import tools.jackson.databind.JsonNode;

/** Explicit allowlist and presence handling: a missing field differs from JSON null. */
final class SocialBody {
    private SocialBody() {}

    static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw SocialFailure.invalid();
        for (var name : node.propertyNames())
            if (!name.equals(field)) throw SocialFailure.invalid();
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw SocialFailure.invalid();
        return SocialText.validate(value.asString());
    }
}
