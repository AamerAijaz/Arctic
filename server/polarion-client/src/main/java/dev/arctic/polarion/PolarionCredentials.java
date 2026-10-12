package dev.arctic.polarion;

import java.util.Objects;

/** Connection settings for Polarion: the REST root (see {@link RestRoot#normalize}) and a personal access token. */
public record PolarionCredentials(String restRoot, String token) {

    public PolarionCredentials {
        Objects.requireNonNull(restRoot, "restRoot");
        Objects.requireNonNull(token, "token");
    }

    @Override
    public String toString() {
        return "PolarionCredentials[restRoot=" + restRoot + ", token=[redacted]]";
    }
}
