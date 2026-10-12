package dev.arctic.polarion;

/**
 * Base error for Polarion calls. Messages are safe to show to users and language models: they never contain the
 * token.
 */
public class PolarionException extends RuntimeException {

    public PolarionException(String message) {
        super(message);
    }
}
