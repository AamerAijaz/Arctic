package dev.arctic.polarion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

import dev.arctic.polarion.testing.FixtureServer;
import dev.arctic.polarion.testing.Fixtures;
import org.junit.jupiter.api.Test;

class PolarionClientTest {

    @Test
    void detailPrefersErrorDetail() {
        assertEquals("d", PolarionErrors.errorDetail(400, "{\"errors\":[{\"detail\":\" d \",\"title\":\"t\"}]}"));
    }

    @Test
    void detailFallsBackToTitleWhenDetailIsEmpty() {
        assertEquals("t", PolarionErrors.errorDetail(400, "{\"errors\":[{\"detail\":\"\",\"title\":\"t\"}]}"));
        assertEquals("t", PolarionErrors.errorDetail(400, "{\"errors\":[{\"detail\":null,\"title\":\"t\"}]}"));
    }

    @Test
    void detailUsesReasonPhraseWhenTruthyDetailIsUnusable() {
        assertEquals("Conflict", PolarionErrors.errorDetail(409, "{\"errors\":[{\"detail\":\"  \",\"title\":\"t\"}]}"));
        assertEquals("Conflict", PolarionErrors.errorDetail(409, "{\"errors\":[{\"detail\":5,\"title\":\"t\"}]}"));
    }

    @Test
    void detailUsesReasonPhraseForOtherJson() {
        assertEquals("Not Found", PolarionErrors.errorDetail(404, "{\"errors\":[]}"));
        assertEquals("Not Found", PolarionErrors.errorDetail(404, "[1]"));
        assertEquals("Not Found", PolarionErrors.errorDetail(404, "{\"errors\":[\"x\"]}"));
    }

    @Test
    void detailUsesRawTextOrReasonPhraseForNonJson() {
        assertEquals("Gateway exploded", PolarionErrors.errorDetail(502, "  Gateway exploded \n"));
        assertEquals("Bad Gateway", PolarionErrors.errorDetail(502, ""));
        assertEquals("Bad Gateway", PolarionErrors.errorDetail(502, "   "));
    }

    @Test
    void apiErrorIsRedacted() {
        var error = assertThrows(PolarionApiException.class,
                () -> PolarionErrors.raiseForStatus(500, "oops secret-1 Bearer abc", "secret-1"));
        assertEquals(500, error.status());
        assertEquals("Polarion request failed (500): oops [redacted] Bearer [redacted]", error.getMessage());
    }

    @Test
    void authAndUnavailableMessagesAreFixed() {
        var auth = assertThrows(PolarionAuthException.class, () -> PolarionErrors.raiseForStatus(401, "tok", "tok"));
        assertEquals(PolarionAuthException.MESSAGE, auth.getMessage());
        var unavailable = assertThrows(PolarionUnavailableException.class,
                () -> PolarionErrors.raiseForStatus(503, "tok", "tok"));
        assertEquals(PolarionUnavailableException.REST_DISABLED, unavailable.getMessage());
    }

    @Test
    void networkFailureIsUnavailable() throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var credentials = new PolarionCredentials("http://127.0.0.1:" + port + "/polarion/rest/v1", "secret-1");
        var client = new PolarionClient(PolarionClients.restClient(), () -> credentials);
        var error = assertThrows(PolarionUnavailableException.class, client::getCurrentUser);
        assertTrue(error.getMessage().startsWith("Could not reach Polarion: "), error.getMessage());
        assertFalse(error.getMessage().contains("secret-1"));
    }

    @Test
    void readsCredentialsForEveryRequest() {
        var scenario = Fixtures.scenario("auth/whoami_success");
        try (var server = FixtureServer.start(scenario.exchanges())) {
            var stale = new PolarionCredentials(RestRoot.normalize(server.origin()), "stale");
            var current = new AtomicReference<>(stale);
            var client = new PolarionClient(PolarionClients.restClient(), current::get);
            current.set(server.credentials().get());
            assertEquals(new CurrentUser("alice", "Alice", "alice@example.com"), client.getCurrentUser());
            server.verify();
        }
    }

    @Test
    void credentialsToStringHidesToken() {
        assertFalse(new PolarionCredentials("https://h/polarion/rest/v1", "secret-1").toString().contains("secret-1"));
    }
}
