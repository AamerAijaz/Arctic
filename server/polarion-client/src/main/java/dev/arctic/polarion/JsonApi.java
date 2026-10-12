package dev.arctic.polarion;

import tools.jackson.databind.JsonNode;

/** Helpers for reading Polarion's JSON:API payloads. */
final class JsonApi {

    private JsonApi() {
    }

    /** {@code payload.data} as an object. */
    static JsonNode singleData(JsonNode payload, String kind) {
        JsonNode data = payload == null ? null : payload.get("data");
        if (data == null || !data.isObject()) {
            throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
        }
        return data;
    }

    /** The string value, or null if the node is absent, not a string, or empty. */
    static String nonEmptyString(JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    /** Python truthiness, which the prototype relies on when picking error details. */
    static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0;
        }
        if (node.isString()) {
            return !node.stringValue().isEmpty();
        }
        return !node.isEmpty();
    }
}
