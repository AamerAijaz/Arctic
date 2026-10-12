package dev.arctic.polarion;

/** Polarion answered with an HTTP error other than 401 or 503, or a client-side check failed with a status. */
public class PolarionApiException extends PolarionException {

    private final int status;
    private final String detail;

    public PolarionApiException(int status, String detail) {
        super("Polarion request failed (" + status + "): " + detail);
        this.status = status;
        this.detail = detail;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return detail;
    }
}
