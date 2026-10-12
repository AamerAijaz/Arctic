package dev.arctic.polarion;

/** Supplies credentials. {@link PolarionClient} calls it for every request, so a replaced token takes effect. */
@FunctionalInterface
public interface CredentialSource {

    /** @throws MissingCredentialsException if the URL or token is not configured */
    PolarionCredentials get();
}
