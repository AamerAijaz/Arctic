package dev.arctic.local.config;

import java.util.Map;

import dev.arctic.polarion.CredentialSource;
import dev.arctic.polarion.MissingCredentialsException;
import dev.arctic.polarion.PolarionCredentials;
import dev.arctic.polarion.RestRoot;

/**
 * Polarion URL from {@code config.json} and token from the {@link SecretStore}, read on every call. With
 * {@code ARCTIC_DEV=1}, developers use {@code POLARION_URL} and {@code POLARION_TOKEN} from the environment instead.
 */
public final class ArcticCredentialSource implements CredentialSource {

    public static final String DEV_ENV = "ARCTIC_DEV";
    public static final String URL_ENV = "POLARION_URL";
    public static final String TOKEN_ENV = "POLARION_TOKEN";

    static final String NOT_SET_UP = "Polarion is not set up yet. Open Arctic and complete setup "
            + "with your Polarion URL and personal access token.";
    static final String DEV_NOT_SET = "ARCTIC_DEV=1 is set, so Arctic reads POLARION_URL and POLARION_TOKEN "
            + "from the environment; set both. Create the token in Polarion under My Account > Personal Access Tokens.";

    private final Map<String, String> env;
    private final ConfigStore config;
    private final SecretStore secrets;

    public ArcticCredentialSource(Map<String, String> env, ConfigStore config, SecretStore secrets) {
        // Not copied: on Windows, System.getenv() looks names up case-insensitively.
        this.env = env;
        this.config = config;
        this.secrets = secrets;
    }

    @Override
    public PolarionCredentials get() {
        if ("1".equals(env.get(DEV_ENV))) {
            return credentials(env.get(URL_ENV), env.get(TOKEN_ENV), DEV_NOT_SET);
        }
        String url = config.read().map(ArcticConfig::polarionUrl).filter(value -> !value.isBlank()).orElse(null);
        if (url == null) {
            throw new MissingCredentialsException(NOT_SET_UP);
        }
        return credentials(url, secrets.read(SecretStore.POLARION_TOKEN).orElse(null), NOT_SET_UP);
    }

    private static PolarionCredentials credentials(String url, String token, String missing) {
        String cleanUrl = url == null ? "" : url.strip();
        String cleanToken = token == null ? "" : token.strip();
        if (cleanUrl.isEmpty() || cleanToken.isEmpty()) {
            throw new MissingCredentialsException(missing);
        }
        return new PolarionCredentials(RestRoot.normalize(cleanUrl), cleanToken);
    }
}
