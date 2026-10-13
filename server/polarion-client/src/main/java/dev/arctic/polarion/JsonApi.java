package dev.arctic.polarion;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /** {@code payload.data} as an array. */
    static List<JsonNode> listData(JsonNode payload, String kind) {
        JsonNode data = payload == null ? null : payload.get("data");
        if (data == null || !data.isArray()) {
            throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
        }
        List<JsonNode> items = new ArrayList<>(data.size());
        data.forEach(items::add);
        return items;
    }

    /** The string value, or null if the node is absent, not a string, or empty. */
    static String nonEmptyString(JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    /**
     * Polarion text: a non-empty string, or {@code {value: "..."}}'s non-empty value.
     * Matches the prototype's {@code _text_value}.
     */
    static String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue().isEmpty() ? null : node.stringValue();
        }
        if (node.isObject()) {
            return nonEmptyString(node.get("value"));
        }
        return null;
    }

    /** Percent-encode one path segment the way Python {@code quote(value, safe="")} does. */
    static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
