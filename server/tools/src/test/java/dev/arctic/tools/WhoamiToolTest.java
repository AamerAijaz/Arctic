package dev.arctic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.arctic.polarion.PolarionClient;
import dev.arctic.polarion.PolarionClients;
import dev.arctic.polarion.testing.FixtureServer;
import dev.arctic.polarion.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

class WhoamiToolTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void registersOneToolNamedWhoami() {
        ToolCallback[] callbacks = callbacks(null);
        assertEquals(1, callbacks.length);
        assertEquals("whoami", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].getToolDefinition().description().contains("personal access token"));
    }

    @Test
    void returnsIdNameAndEmail() {
        assertEquals(json.readTree("{\"id\":\"alice\",\"name\":\"Alice\",\"email\":\"alice@example.com\"}"),
                json.readTree(call("auth/whoami_success")));
    }

    @Test
    void keepsMissingEmailAsNull() {
        assertEquals(json.readTree("{\"id\":\"bob\",\"name\":\"Bob\",\"email\":null}"),
                json.readTree(call("auth/whoami_missing_email")));
    }

    @Test
    void authFailureIsAToolErrorWithoutToken() {
        String message = callFailing("auth/whoami_unauthorized");
        assertTrue(message.contains("rejected the token"), message);
        assertFalse(message.contains(Fixtures.TOKEN), message);
    }

    @Test
    void apiErrorIsRedacted() {
        String message = callFailing("auth/whoami_api_error_redacts_token");
        assertEquals("Polarion request failed (400): bad field Bearer [redacted]", message);
    }

    private String call(String scenario) {
        try (var server = FixtureServer.start(Fixtures.scenario(scenario).exchanges())) {
            var client = new PolarionClient(PolarionClients.restClient(), server.credentials());
            String result = callbacks(client)[0].call("{}");
            server.verify();
            return result;
        }
    }

    private String callFailing(String scenario) {
        try (var server = FixtureServer.start(Fixtures.scenario(scenario).exchanges())) {
            var client = new PolarionClient(PolarionClients.restClient(), server.credentials());
            var error = assertThrows(RuntimeException.class, () -> callbacks(client)[0].call("{}"));
            server.verify();
            return error.getMessage();
        }
    }

    private static ToolCallback[] callbacks(PolarionClient client) {
        var tools = new ToolsConfiguration();
        return MethodToolCallbackProvider.builder().toolObjects(tools.whoamiTool(client)).build().getToolCallbacks();
    }
}
