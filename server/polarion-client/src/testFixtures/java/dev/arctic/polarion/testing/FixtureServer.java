package dev.arctic.polarion.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.arctic.polarion.CredentialSource;
import dev.arctic.polarion.PolarionCredentials;
import dev.arctic.polarion.RestRoot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * A local HTTP server standing in for {@link Fixtures#BASE_URL}. It expects the recorded requests in order, answers
 * each with its recorded response, and collects every mismatch for {@link #verify()}.
 */
public final class FixtureServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Scenario.Exchange> exchanges;
    private final AtomicInteger received = new AtomicInteger();
    private final List<String> failures = new CopyOnWriteArrayList<>();

    private FixtureServer(List<Scenario.Exchange> exchanges) throws IOException {
        this.exchanges = List.copyOf(exchanges);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FixtureServer start(List<Scenario.Exchange> exchanges) {
        try {
            return new FixtureServer(exchanges);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces {@link Fixtures#BASE_URL} in the fixtures, for example {@code http://127.0.0.1:54321}. */
    public String origin() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /** Credentials for this server with {@link Fixtures#TOKEN}. */
    public CredentialSource credentials() {
        var credentials = new PolarionCredentials(RestRoot.normalize(origin()), Fixtures.TOKEN);
        return () -> credentials;
    }

    /** Fails if any request did not match, an extra request arrived, or a recorded exchange was not used. */
    public void verify() {
        List<String> problems = new ArrayList<>(failures);
        int used = received.get();
        if (used < exchanges.size()) {
            problems.add("only " + used + " of " + exchanges.size() + " exchanges were used");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("Fixture replay failed:\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange http) throws IOException {
        try (http) {
            byte[] body = http.getRequestBody().readAllBytes();
            int index = received.getAndIncrement();
            String where = "request " + (index + 1) + " " + http.getRequestMethod() + " " + http.getRequestURI();
            if (index >= exchanges.size()) {
                failures.add(where + ": unexpected extra request");
                respond(http, 500, null);
                return;
            }
            Scenario.Exchange exchange = exchanges.get(index);
            List<String> problems = mismatches(exchange.request(), http, body);
            if (!problems.isEmpty()) {
                problems.forEach(problem -> failures.add(where + ": " + problem));
                respond(http, 500, null);
                return;
            }
            JsonNode response = exchange.response();
            respond(http, response.path("status").asInt(), response.get("body"));
        } catch (RuntimeException e) {
            failures.add("fixture server error: " + e);
            throw e;
        }
    }

    private static List<String> mismatches(JsonNode expected, HttpExchange http, byte[] body) {
        List<String> problems = new ArrayList<>();
        check(problems, "method", expected.path("method").asString(), http.getRequestMethod());
        check(problems, "path", expected.path("path").asString(), http.getRequestURI().getRawPath());

        Map<String, String> wantQuery = new LinkedHashMap<>();
        expected.path("query").properties().forEach(entry -> wantQuery.put(entry.getKey(), entry.getValue().asString()));
        Map<String, String> query = parseQuery(http.getRequestURI().getRawQuery(), problems);
        check(problems, "query", wantQuery, query);

        var headers = http.getRequestHeaders();
        check(problems, "Authorization header", "Bearer " + Fixtures.TOKEN, headers.getFirst("Authorization"));
        check(problems, "Accept header", "application/json", headers.getFirst("Accept"));
        if (expected.has("body")) {
            check(problems, "Content-Type header", "application/json", headers.getFirst("Content-Type"));
            try {
                check(problems, "body", expected.get("body"), Fixtures.JSON.readTree(body));
            } catch (JacksonException e) {
                problems.add("body is not JSON: " + new String(body, StandardCharsets.UTF_8));
            }
        } else if (body.length > 0) {
            problems.add("expected no body but got " + new String(body, StandardCharsets.UTF_8));
        }
        return problems;
    }

    private static Map<String, String> parseQuery(String rawQuery, List<String> problems) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if (query.put(key, value) != null) {
                problems.add("repeated query key " + key);
            }
        }
        return query;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void check(List<String> problems, String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            problems.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void respond(HttpExchange http, int status, JsonNode body) throws IOException {
        if (body == null) {
            http.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = Fixtures.JSON.writeValueAsBytes(body);
        http.getResponseHeaders().set("Content-Type", "application/json");
        http.sendResponseHeaders(status, bytes.length);
        http.getResponseBody().write(bytes);
    }
}
