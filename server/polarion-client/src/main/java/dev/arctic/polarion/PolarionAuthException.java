package dev.arctic.polarion;

/** Polarion rejected the personal access token (HTTP 401). */
public class PolarionAuthException extends PolarionException {

    static final String MESSAGE =
            "Polarion rejected the token. Create a personal access token in Polarion and set POLARION_TOKEN.";

    public PolarionAuthException() {
        super(MESSAGE);
    }
}
