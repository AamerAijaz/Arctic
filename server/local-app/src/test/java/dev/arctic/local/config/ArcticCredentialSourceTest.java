package dev.arctic.local.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.arctic.polarion.MissingCredentialsException;
import dev.arctic.polarion.PolarionCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArcticCredentialSourceTest {

    private static final String ROOT = "https://polarion.example.com/polarion/rest/v1";

    @TempDir
    Path dir;

    private final SecretStore secrets = new InMemorySecretStore();

    @Test
    void combinesConfigUrlAndStoredToken() {
        configure("  https://polarion.example.com/polarion/ ");
        secrets.write(SecretStore.POLARION_TOKEN, " tok-1 ");

        assertThat(source(Map.of()).get()).isEqualTo(new PolarionCredentials(ROOT, "tok-1"));
    }

    @Test
    void picksUpAReplacedTokenOnTheNextCall() {
        configure("https://polarion.example.com");
        var source = source(Map.of());
        secrets.write(SecretStore.POLARION_TOKEN, "old");
        assertThat(source.get().token()).isEqualTo("old");

        secrets.write(SecretStore.POLARION_TOKEN, "new");
        assertThat(source.get().token()).isEqualTo("new");
    }

    @Test
    void missingConfigTellsUserToCompleteSetup() {
        secrets.write(SecretStore.POLARION_TOKEN, "tok");

        assertNotSetUp(source(Map.of()));
    }

    @Test
    void missingOrBlankTokenTellsUserToCompleteSetup() {
        configure("https://polarion.example.com");
        assertNotSetUp(source(Map.of()));

        secrets.write(SecretStore.POLARION_TOKEN, "  ");
        assertNotSetUp(source(Map.of()));
    }

    @Test
    void blankUrlTellsUserToCompleteSetup() {
        configure(" ");
        secrets.write(SecretStore.POLARION_TOKEN, "tok");

        assertNotSetUp(source(Map.of()));
    }

    @Test
    void invalidUrlIsReported() {
        configure("polarion.example.com");
        secrets.write(SecretStore.POLARION_TOKEN, "tok");

        assertThatThrownBy(() -> source(Map.of()).get())
                .isInstanceOf(MissingCredentialsException.class)
                .hasMessageContaining("http(s) URL");
    }

    @Test
    void envIsIgnoredWithoutDevFlag() {
        var env = Map.of("POLARION_URL", "https://dev.example", "POLARION_TOKEN", "dev-tok", "ARCTIC_DEV", "true");

        assertNotSetUp(source(env));
    }

    @Test
    void devFlagUsesEnvironmentInsteadOfConfig() {
        configure("https://configured.example");
        secrets.write(SecretStore.POLARION_TOKEN, "stored");
        var env = Map.of("ARCTIC_DEV", "1", "POLARION_URL", "https://polarion.example.com", "POLARION_TOKEN", "dev-tok");

        assertThat(source(env).get()).isEqualTo(new PolarionCredentials(ROOT, "dev-tok"));
    }

    @Test
    void devFlagWithMissingEnvNamesTheVariables() {
        configure("https://configured.example");
        secrets.write(SecretStore.POLARION_TOKEN, "stored");

        assertThatThrownBy(() -> source(Map.of("ARCTIC_DEV", "1", "POLARION_URL", "https://polarion.example.com")).get())
                .isInstanceOf(MissingCredentialsException.class)
                .hasMessageContaining("POLARION_URL")
                .hasMessageContaining("POLARION_TOKEN");
    }

    private void configure(String url) {
        new ConfigStore(dir).write(new ArcticConfig(url, List.of(), null));
    }

    private ArcticCredentialSource source(Map<String, String> env) {
        return new ArcticCredentialSource(env, new ConfigStore(dir), secrets);
    }

    private static void assertNotSetUp(ArcticCredentialSource source) {
        assertThatThrownBy(source::get)
                .isInstanceOf(MissingCredentialsException.class)
                .hasMessage(ArcticCredentialSource.NOT_SET_UP)
                .hasMessageContaining("Open Arctic")
                .hasMessageNotContaining("POLARION_")
                .hasMessageNotContaining("ARCTIC_");
    }
}
