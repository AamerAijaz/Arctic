package dev.arctic.polarion;

/** Polarion could not be reached, or its REST API is disabled (HTTP 503). */
public class PolarionUnavailableException extends PolarionException {

    static final String REST_DISABLED = "Polarion REST API is not enabled on this instance.";

    public PolarionUnavailableException(String message) {
        super(message);
    }
}
