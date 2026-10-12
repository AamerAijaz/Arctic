package dev.arctic.polarion;

import tools.jackson.databind.JsonNode;

/** The Polarion user who owns the token. {@code name} and {@code email} are null when Polarion omits them. */
public record CurrentUser(String id, String name, String email) {

    static CurrentUser fromJson(JsonNode payload) {
        JsonNode data = JsonApi.singleData(payload, "user");
        String id = JsonApi.nonEmptyString(data.get("id"));
        if (id == null) {
            throw new PolarionException("Polarion returned a user response without an id.");
        }
        JsonNode attributes = data.path("attributes");
        return new CurrentUser(id,
                JsonApi.nonEmptyString(attributes.get("name")),
                JsonApi.nonEmptyString(attributes.get("email")));
    }
}
