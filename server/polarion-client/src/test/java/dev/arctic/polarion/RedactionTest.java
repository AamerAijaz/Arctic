package dev.arctic.polarion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class RedactionTest {

    static Stream<Named<JsonNode>> cases() {
        return Fixtures.json("redaction.json").path("cases").valueStream()
                .map(c -> Named.of(c.path("name").asString(), c));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void matchesFixture(JsonNode c) {
        String token = c.path("token").isNull() ? null : c.path("token").asString();
        assertEquals(c.path("expected").asString(), Redaction.redact(c.path("text").asString(), token));
    }
}
