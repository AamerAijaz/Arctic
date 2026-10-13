package dev.arctic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.arctic.polarion.PolarionClient;
import dev.arctic.polarion.PolarionClients;
import dev.arctic.polarion.PolarionCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

class PolarionServerToolTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void registersOneToolNamedPolarionServer() {
        ToolCallback[] callbacks = callbacks(client("http://127.0.0.1:9/polarion/rest/v1", "secret-token"));
        assertEquals(1, callbacks.length);
        assertEquals("polarion_server", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].getToolDefinition().description().contains("REST root"));
        assertTrue(callbacks[0].getToolDefinition().description().contains("does not take a token"));
    }

    @Test
    void returnsRestRootAndOmitsTheToken() {
        String result = callbacks(client("http://127.0.0.1:9/polarion/rest/v1", "secret-token"))[0].call("{}");
        assertEquals(
                json.readTree("{\"restRoot\":\"http://127.0.0.1:9/polarion/rest/v1\"}"),
                json.readTree(result));
        assertFalse(result.contains("secret-token"), result);
    }

    private static PolarionClient client(String restRoot, String token) {
        PolarionCredentials credentials = new PolarionCredentials(restRoot, token);
        return new PolarionClient(PolarionClients.restClient(), () -> credentials);
    }

    private static ToolCallback[] callbacks(PolarionClient client) {
        var tools = new ToolsConfiguration();
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.polarionServerTool(client))
                .build()
                .getToolCallbacks();
    }
}
