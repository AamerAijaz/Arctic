package dev.arctic.polarion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class RestRootTest {

    static Stream<Named<JsonNode>> cases() {
        return Fixtures.json("url_normalization.json").path("cases").valueStream()
                .map(c -> Named.of(c.path("name").asString(), c));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void matchesFixture(JsonNode c) {
        String input = c.path("input").asString();
        if (c.has("error")) {
            var error = assertThrows(PolarionException.class, () -> RestRoot.normalize(input));
            assertEquals(c.path("error").path("type").asString().replace("Error", "Exception"),
                    error.getClass().getSimpleName());
        } else {
            assertEquals(c.path("expected").asString(), RestRoot.normalize(input));
        }
    }

    @Test
    void dropsParamsOfLastSegmentLikeUrlparse() {
        assertEquals("https://h.example/polarion/rest/v1", RestRoot.normalize("https://h.example/polarion;jsessionid=1"));
    }

    @Test
    void keepsUserInfoAndPort() {
        assertEquals("https://u@h.example:8443/polarion/rest/v1", RestRoot.normalize("https://u@h.example:8443"));
    }

    @Test
    void rejectsUnbalancedIpv6Brackets() {
        assertThrows(MissingCredentialsException.class, () -> RestRoot.normalize("https://[::1/polarion"));
    }
}
