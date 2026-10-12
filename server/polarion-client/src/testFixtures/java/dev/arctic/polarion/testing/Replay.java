package dev.arctic.polarion.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.arctic.polarion.CredentialSource;
import dev.arctic.polarion.PolarionApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/** Replays a {@link Scenario} against a {@link FixtureServer} and checks the outcome against {@code expect}. */
public final class Replay {

    /** Results are compared in the prototype's {@code to_dict()} shape: snake_case keys, nulls included. */
    private static final JsonMapper RESULT_JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    /** Calls the operation under test with credentials pointing at the fixture server. */
    @FunctionalInterface
    public interface Invocation {
        Object invoke(CredentialSource credentials) throws Exception;
    }

    private Replay() {
    }

    public static void run(Scenario scenario, Invocation invocation) {
        Object result = null;
        Exception error = null;
        try (var server = FixtureServer.start(scenario.exchanges())) {
            try {
                result = invocation.invoke(server.credentials());
            } catch (Exception e) {
                error = e;
            }
            server.verify();
        }

        JsonNode expect = scenario.expect();
        if (expect.has("error")) {
            assertError(expect.get("error"), result, error);
        } else {
            if (error != null) {
                fail(scenario + ": expected a result but the call failed", error);
            }
            JsonNode actual = result == null ? NullNode.getInstance() : RESULT_JSON.valueToTree(result);
            assertEquals(expect.get("result"), actual, scenario + ": result");
        }
    }

    private static void assertError(JsonNode want, Object result, Exception error) {
        assertNotNull(error, () -> "expected " + want + " but the call returned " + result);
        String type = want.path("type").asString().replaceFirst("Error$", "Exception");
        assertEquals(type, error.getClass().getSimpleName(), () -> "error type of " + error);
        if (want.has("status")) {
            var api = assertInstanceOf(PolarionApiException.class, error);
            assertEquals(want.get("status").asInt(), api.status(), "status");
        }
        String message = error.getMessage();
        if (want.has("message_contains")) {
            String needle = want.get("message_contains").asString();
            assertTrue(message.contains(needle), () -> "message '" + message + "' should contain '" + needle + "'");
        }
        if (want.has("message_excludes")) {
            String needle = want.get("message_excludes").asString();
            assertFalse(message.contains(needle), () -> "message '" + message + "' must not contain '" + needle + "'");
        }
    }
}
