package dev.arctic.polarion.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FixtureServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final List<Scenario.Exchange> POST = List.of(new Scenario.Exchange(
            json("{\"method\":\"POST\",\"path\":\"/polarion/rest/v1/a%2Fb\",\"query\":{\"q\":\"x y\"},\"body\":{\"k\":[1,2]}}"),
            json("{\"status\":201,\"body\":{\"ok\":true}}")));

    @Test
    void returnsRecordedResponseForMatchingRequest() throws Exception {
        try (var server = FixtureServer.start(POST)) {
            HttpResponse<String> response = send(server, "POST", "/polarion/rest/v1/a%2Fb?q=x%20y", "{ \"k\" : [1, 2] }",
                    "application/json");
            assertEquals(201, response.statusCode());
            assertEquals(json("{\"ok\":true}"), json(response.body()));
            assertDoesNotThrow(server::verify);
        }
    }

    @Test
    void reportsMismatchedBodyAndHeaders() throws Exception {
        try (var server = FixtureServer.start(POST)) {
            send(server, "POST", "/polarion/rest/v1/a%2Fb?q=x%20y", "{\"k\":[2,1]}", "text/plain");
            var error = assertThrows(AssertionError.class, server::verify);
            assertTrue(error.getMessage().contains("body"), error.getMessage());
            assertTrue(error.getMessage().contains("Content-Type"), error.getMessage());
        }
    }

    @Test
    void reportsWrongPathAndRepeatedQuery() throws Exception {
        try (var server = FixtureServer.start(POST)) {
            send(server, "POST", "/polarion/rest/v1/a/b?q=x%20y&q=z", "{\"k\":[1,2]}", "application/json");
            var error = assertThrows(AssertionError.class, server::verify);
            assertTrue(error.getMessage().contains("path"), error.getMessage());
            assertTrue(error.getMessage().contains("repeated query key q"), error.getMessage());
        }
    }

    @Test
    void reportsExtraAndUnusedRequests() throws Exception {
        try (var server = FixtureServer.start(List.of())) {
            assertEquals(500, send(server, "GET", "/x", null, null).statusCode());
            assertTrue(assertThrows(AssertionError.class, server::verify).getMessage().contains("unexpected extra"));
        }
        try (var server = FixtureServer.start(POST)) {
            assertTrue(assertThrows(AssertionError.class, server::verify).getMessage().contains("0 of 1"));
        }
    }

    private static HttpResponse<String> send(FixtureServer server, String method, String pathAndQuery, String body,
            String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.origin() + pathAndQuery))
                .header("Authorization", "Bearer " + Fixtures.TOKEN)
                .header("Accept", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(String text) {
        return Fixtures.JSON.readTree(text);
    }
}
