package com.arctic.mcp.polarion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks Polarion's REST API. Callers pass a {@link PolarionCredentialsSource}; this type never reads one. */
public final class PolarionClient {

    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
    private static final String REJECTED_TOKEN =
            "Polarion rejected the token. Create a personal access token in Polarion and set POLARION_TOKEN.";
    private static final String REST_DISABLED = "Polarion REST API is not enabled on this instance.";
    private static final String UNEXPECTED_USER = "Polarion returned an unexpected user response.";
    private static final String MISSING_ID = "Polarion returned a user response without an id.";

    private final PolarionCredentialsSource credentials;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public PolarionClient(PolarionCredentialsSource credentials, RestClient http) {
        this.credentials = credentials;
        this.http = http;
    }

    public CurrentUser getCurrentUser() {
        PolarionCredentials current = credentials.get();
        String url = current.restRoot() + "/user";
        try {
            return http.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.token())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readUser(response.getStatusCode(), body(response), current.token()));
        } catch (RestClientException exception) {
            throw new PolarionUnavailableException(
                    "Could not reach Polarion: " + redact(String.valueOf(exception.getMessage()), current.token()));
        }
    }

    private CurrentUser readUser(HttpStatusCode status, String body, String token) {
        int code = status.value();
        if (code == 401) {
            throw new PolarionAuthException(REJECTED_TOKEN);
        }
        if (code == 503) {
            throw new PolarionUnavailableException(REST_DISABLED);
        }
        if (code >= 400) {
            throw new PolarionApiException(
                    "Polarion request failed (" + code + "): " + redact(errorDetail(body, status), token));
        }
        return parseCurrentUser(body);
    }

    private static String body(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private CurrentUser parseCurrentUser(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException exception) {
            throw new PolarionException(UNEXPECTED_USER);
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            throw new PolarionException(UNEXPECTED_USER);
        }
        JsonNode id = data.get("id");
        if (id == null || !id.isString() || id.asString().isEmpty()) {
            throw new PolarionException(MISSING_ID);
        }
        String name = null;
        String email = null;
        JsonNode attributes = data.get("attributes");
        if (attributes != null && attributes.isObject()) {
            name = textOrNull(attributes.get("name"));
            email = textOrNull(attributes.get("email"));
        }
        return new CurrentUser(id.asString(), name, email);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isString() || node.asString().isEmpty()) {
            return null;
        }
        return node.asString();
    }

    private String errorDetail(String body, HttpStatusCode status) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode errors = root.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                JsonNode first = errors.get(0);
                String detail = textOrNull(first.get("detail"));
                if (detail == null) {
                    detail = textOrNull(first.get("title"));
                }
                if (detail != null) {
                    return detail;
                }
            }
        } catch (RuntimeException ignored) {
            String text = body == null ? "" : body.trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return status.toString();
    }

    static String redact(String text, String token) {
        String cleaned = text == null ? "" : BEARER.matcher(text).replaceAll("Bearer [redacted]");
        if (token != null && !token.isEmpty()) {
            cleaned = cleaned.replace(token, "[redacted]");
        }
        return cleaned;
    }
}
