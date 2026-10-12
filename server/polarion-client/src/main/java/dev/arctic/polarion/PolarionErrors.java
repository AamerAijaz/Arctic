package dev.arctic.polarion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maps Polarion HTTP error status and bodies onto the Polarion exception types. */
final class PolarionErrors {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PolarionErrors() {
    }

    static void raiseForStatus(int status, String body, String token) {
        if (status < 400) {
            return;
        }
        if (status == 401) {
            throw new PolarionAuthException();
        }
        if (status == 503) {
            throw new PolarionUnavailableException(PolarionUnavailableException.REST_DISABLED);
        }
        throw new PolarionApiException(status, Redaction.redact(errorDetail(status, body), token));
    }

    /** {@code errors[0].detail}, else {@code errors[0].title}, else the raw text, else the reason phrase. */
    static String errorDetail(int status, String body) {
        JsonNode payload;
        try {
            payload = JSON.readTree(body);
        } catch (JacksonException e) {
            String text = body.strip();
            return text.isEmpty() ? ReasonPhrases.of(status) : text;
        }
        JsonNode errors = payload == null ? null : payload.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty() && errors.get(0).isObject()) {
            JsonNode first = errors.get(0);
            JsonNode detail = JsonApi.truthy(first.get("detail")) ? first.get("detail") : first.get("title");
            if (detail != null && detail.isString() && !detail.stringValue().isBlank()) {
                return detail.stringValue().strip();
            }
        }
        return ReasonPhrases.of(status);
    }
}
