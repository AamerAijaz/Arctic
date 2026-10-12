package dev.arctic.polarion;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the Polarion REST API as the user who owns the configured personal access token. */
public final class PolarionClient {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final RestClient http;
    private final CredentialSource credentials;

    public PolarionClient(RestClient http, CredentialSource credentials) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /** {@code GET /user}: the Polarion user for this token. */
    public CurrentUser getCurrentUser() {
        return CurrentUser.fromJson(request("GET", "/user", Map.of(), null));
    }

    /**
     * Sends one request relative to the REST root.
     *
     * @return the parsed JSON body, or null for 204 and empty bodies
     */
    private JsonNode request(String method, String path, Map<String, String> query, JsonNode body) {
        PolarionCredentials creds = credentials.get();
        String payload;
        try {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method))
                    .uri(URI.create(creds.restRoot() + path + queryString(query)))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + creds.token())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
            }
            payload = spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> PolarionErrors.raiseForStatus(
                            res.getStatusCode().value(),
                            StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8),
                            creds.token()))
                    .body(String.class);
        } catch (ResourceAccessException | IllegalArgumentException e) {
            if (causedByInterrupt(e)) {
                Thread.currentThread().interrupt();
            }
            throw unreachable(e, creds.token());
        }
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(payload);
        } catch (JacksonException e) {
            throw new PolarionException("Polarion returned a response that is not JSON.");
        }
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        return query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean causedByInterrupt(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    private static PolarionUnavailableException unreachable(Exception e, String token) {
        String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
        return new PolarionUnavailableException("Could not reach Polarion: " + Redaction.redact(reason, token));
    }
}
