package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.PolarionConfiguration;
import com.arctic.mcp.polarion.CurrentUser;
import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionCredentials;
import com.arctic.mcp.polarion.PolarionCredentialsSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WhoamiToolTest {

  private static final String TOKEN = "super-secret-token";

  @Test
  void whoamiReturnsThePolarionUserAndSendsTheBearerToken() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"users","id":"alice","attributes":{"name":"Alice","email":"alice@example.com"}}}
                """,
                MediaType.APPLICATION_JSON));

    CurrentUser user = harness.tool.whoami();

    assertEquals("alice", user.id());
    assertEquals("Alice", user.name());
    assertEquals("alice@example.com", user.email());
    harness.server.verify();
  }

  @Test
  void whoamiReturnsNullEmailWhenPolarionOmitsIt() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"users","id":"bob","attributes":{"name":"Bob"}}}
                """,
                MediaType.APPLICATION_JSON));

    CurrentUser user = harness.tool.whoami();

    assertEquals("bob", user.id());
    assertEquals("Bob", user.name());
    assertNull(user.email());
  }

  @Test
  void rejectedTokenDoesNotEchoTheToken() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"" + TOKEN + "\"}]}"));

    RuntimeException error = assertThrows(RuntimeException.class, harness.tool::whoami);

    assertEquals(
        "Polarion rejected the token. Create a personal access token in Polarion and set"
            + " POLARION_TOKEN.",
        error.getMessage());
    assertFalse(error.getMessage().contains(TOKEN));
    assertNull(error.getCause());
  }

  @Test
  void disabledRestApiIsReportedAsNotEnabled() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"title\":\"Service Unavailable\"}]}"));

    RuntimeException error = assertThrows(RuntimeException.class, harness.tool::whoami);

    assertEquals("Polarion REST API is not enabled on this instance.", error.getMessage());
  }

  @Test
  void otherErrorsRedactTheTokenAndBearerValue() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"bad field Bearer " + TOKEN + "\"}]}"));

    RuntimeException error = assertThrows(RuntimeException.class, harness.tool::whoami);

    assertEquals("Polarion request failed (400): bad field Bearer [redacted]", error.getMessage());
    assertFalse(error.getMessage().contains(TOKEN));
  }

  @Test
  void unreachableHostRedactsTheToken() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(withException(new IOException("timed out " + TOKEN)));

    RuntimeException error = assertThrows(RuntimeException.class, harness.tool::whoami);

    assertTrue(error.getMessage().startsWith("Could not reach Polarion:"));
    assertTrue(error.getMessage().contains("[redacted]"));
    assertFalse(error.getMessage().contains(TOKEN));
    assertNull(error.getCause());
  }

  @Test
  void eachCallReadsCredentialsAgain() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    AtomicReference<String> token = new AtomicReference<>("first-token");
    PolarionCredentialsSource source =
        () -> new PolarionCredentials("https://polarion.example.com/polarion/rest/v1", token.get());
    WhoamiTool tool = new WhoamiTool(new PolarionClient(source, builder.build()));
    String userJson =
        """
        {"data":{"type":"users","id":"alice","attributes":{"name":"Alice","email":"alice@example.com"}}}
        """;
    server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andExpect(header("Authorization", "Bearer first-token"))
        .andRespond(withSuccess(userJson, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andExpect(header("Authorization", "Bearer second-token"))
        .andRespond(withSuccess(userJson, MediaType.APPLICATION_JSON));

    tool.whoami();
    token.set("second-token");
    tool.whoami();

    server.verify();
  }

  @Test
  void whoamiIsExposedWithNoTokenArgument() {
    Harness harness = harness();
    ToolDefinition definition = whoami(harness).getToolDefinition();
    assertEquals("whoami", definition.name());
    assertEquals(
        """
        Return the Polarion user for the configured personal access token.

        Call this before creating or changing Polarion data so you know which
        user the action will run as. This tool does not take a token.
        """
            .strip(),
        definition.description().strip());
    assertFalse(definition.inputSchema().contains("token"));
  }

  @Test
  void whoamiResultKeepsANullEmailOnTheWire() {
    Harness harness = harness();
    harness
        .server
        .expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"users","id":"bob","attributes":{"name":"Bob"}}}
                """,
                MediaType.APPLICATION_JSON));
    ToolCallback tool = whoami(harness);

    String json = tool.call("{}");

    assertTrue(json.contains("\"id\":\"bob\"") || json.contains("\"id\": \"bob\""));
    assertTrue(json.contains("\"email\":null") || json.contains("\"email\": null"));
    assertFalse(json.contains(TOKEN));
  }

  private static ToolCallback whoami(Harness harness) {
    ToolCallback[] tools =
        new PolarionConfiguration()
            .arcticTools(
                harness.tool,
                new ProjectTools(harness.client),
                new WorkItemTools(harness.client),
                new LinkTools(harness.client),
                new DocumentTools(harness.client),
                new UserTools(harness.client),
                new TestStepTools(harness.client))
            .getToolCallbacks();
    return Arrays.stream(tools)
        .filter(tool -> "whoami".equals(tool.getToolDefinition().name()))
        .findFirst()
        .orElseThrow();
  }

  private static Harness harness() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    PolarionCredentialsSource source =
        () -> new PolarionCredentials("https://polarion.example.com/polarion/rest/v1", TOKEN);
    PolarionClient client = new PolarionClient(source, builder.build());
    return new Harness(server, client, new WhoamiTool(client));
  }

  private record Harness(MockRestServiceServer server, PolarionClient client, WhoamiTool tool) {}
}
