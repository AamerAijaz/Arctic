package dev.arctic.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServeModeTest {

    private static final String TOKEN = "test-token-123";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @TempDir
    static Path serverDir;

    private Process server;
    private int port;

    @BeforeAll
    void startServer() throws Exception {
        port = freePort();
        server = startServe(serverDir, port);
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.destroyForcibly();
        }
    }

    @Test
    void healthWithTokenIsReady() throws Exception {
        HttpResponse<String> response = get(port, "Bearer " + TOKEN, "http://evil.example");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("ready");
        assertThat(body.path("version").asString()).isNotBlank();
        assertThat(response.headers().map().keySet())
                .noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get(port, null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("kind").asString()).isEqualTo("unauthorized");
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get(port, "Bearer wrong", null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void foreignHostIsForbidden() throws Exception {
        // java.net.http does not allow overriding Host, so send the request by hand.
        try (var socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.getOutputStream().write(("GET /health HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Authorization: Bearer " + TOKEN + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String body = response.substring(response.indexOf("\r\n\r\n") + 4);

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(json.readTree(body).path("kind").asString()).isEqualTo("forbidden_host");
        }
    }

    @Test
    void refusesToStartWithoutToken(@TempDir Path dir) throws Exception {
        Process process = AppProcess.start(dir, Map.of(), "serve", "--port", Integer.toString(freePort()));
        try {
            assertThat(process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void exitsWhenParentProcessEnds(@TempDir Path dir) throws Exception {
        Path sleeper = Files.writeString(dir.resolve("Sleeper.java"),
                "class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }");
        Process parent = new ProcessBuilder(AppProcess.java(), sleeper.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        int childPort = freePort();
        Process child = startServe(dir, childPort, "--parent-pid", Long.toString(parent.pid()));
        try {
            parent.destroyForcibly().waitFor();
            assertThat(child.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("serve exits after parent process ends")
                    .isTrue();
            assertThat(child.exitValue()).isZero();
        } finally {
            parent.destroyForcibly();
            child.destroyForcibly();
        }
    }

    private Process startServe(Path dir, int port, String... extraArgs) throws Exception {
        String[] args = new String[3 + extraArgs.length];
        args[0] = "serve";
        args[1] = "--port";
        args[2] = Integer.toString(port);
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        Process process = AppProcess.start(dir, Map.of(ArcticApplication.TOKEN_ENV, TOKEN), args);
        awaitReady(process, port);
        return process;
    }

    private void awaitReady(Process process, int port) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new AssertionError("serve exited early with code " + process.exitValue());
            }
            try {
                if (get(port, "Bearer " + TOKEN, null).statusCode() == 200) {
                    return;
                }
            } catch (IOException notListeningYet) {
                // retry until the deadline
            }
            Thread.sleep(200);
        }
        throw new AssertionError("serve did not become ready within " + TIMEOUT);
    }

    private HttpResponse<String> get(int port, String authorization, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(10));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
