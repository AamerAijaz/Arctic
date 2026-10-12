package dev.arctic.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.arctic.polarion.testing.FixtureServer;
import dev.arctic.polarion.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StdioProtocolTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void speaksMcpOnStdoutAndExitsWhenStdinCloses(@TempDir Path dir) throws Exception {
        Process process = AppProcess.start(dir, Map.of(), "stdio");
        try {
            var session = new Session(process);

            send(session.stdin, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
            JsonNode tools = awaitResponse(session.stdout, 2).path("result").path("tools");
            assertThat(tools.valueStream().map(tool -> tool.path("name").asString())).containsExactly("whoami");

            JsonNode result = callWhoami(session, 3);
            assertThat(result.path("isError").asBoolean()).as("whoami without setup is a tool error").isTrue();
            assertThat(result.path("content").path(0).path("text").asString())
                    .contains("Open Arctic and complete setup")
                    .doesNotContain("POLARION_");

            session.stdin.close();
            assertThat(process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("process exits after stdin closes")
                    .isTrue();
            for (Optional<String> line; (line = session.stdout.next(TIMEOUT)).isPresent(); ) {
                parseMessage(line.get());
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void whoamiCallsPolarionWithDeveloperCredentials(@TempDir Path dir) throws Exception {
        var scenario = Fixtures.scenario("auth/whoami_success");
        try (var polarion = FixtureServer.start(scenario.exchanges())) {
            Process process = AppProcess.start(dir, Map.of(
                    "ARCTIC_DEV", "1",
                    "POLARION_URL", polarion.origin(),
                    "POLARION_TOKEN", Fixtures.TOKEN), "stdio");
            try {
                JsonNode result = callWhoami(new Session(process), 2);

                assertThat(result.path("isError").asBoolean()).as("isError in %s", result).isFalse();
                JsonNode user = json.readTree(result.path("content").path(0).path("text").asString());
                assertThat(user).isEqualTo(scenario.expect().path("result"));
                polarion.verify();
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private JsonNode callWhoami(Session session, int id) throws Exception {
        send(session.stdin, Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", "whoami", "arguments", Map.of())));
        return awaitResponse(session.stdout, id).path("result");
    }

    /** An initialized MCP session over the process's stdin and stdout. */
    private final class Session {

        final AppProcess.Lines stdout;
        final Writer stdin;

        Session(Process process) throws Exception {
            stdout = new AppProcess.Lines(process.getInputStream());
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            send(stdin, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "arctic-test", "version", "0"))));
            JsonNode initialize = awaitResponse(stdout, 1);
            assertThat(initialize.path("result").path("serverInfo").path("name").asString()).isEqualTo("arctic");
            send(stdin, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        }
    }

    private void send(Writer stdin, Map<String, ?> message) throws Exception {
        stdin.write(json.writeValueAsString(message));
        stdin.write('\n');
        stdin.flush();
    }

    private JsonNode awaitResponse(AppProcess.Lines stdout, int id) throws Exception {
        while (true) {
            String line = stdout.next(TIMEOUT)
                    .orElseThrow(() -> new AssertionError("stdout closed before response " + id));
            JsonNode message = parseMessage(line);
            if (message.path("id").asInt(-1) == id) {
                assertThat(message.has("error")).as("error in response: %s", line).isFalse();
                return message;
            }
        }
    }

    private JsonNode parseMessage(String line) {
        JsonNode message = json.readTree(line);
        assertThat(message.isObject()).as("stdout line is a JSON object: %s", line).isTrue();
        assertThat(message.path("jsonrpc").asString()).as("jsonrpc version in: %s", line).isEqualTo("2.0");
        return message;
    }
}
