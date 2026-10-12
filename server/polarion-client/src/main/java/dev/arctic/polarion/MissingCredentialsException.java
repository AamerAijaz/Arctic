package dev.arctic.polarion;

/** The Polarion URL or token is not configured, or the URL is not usable. */
public class MissingCredentialsException extends PolarionException {

    public MissingCredentialsException(String message) {
        super(message);
    }
}
