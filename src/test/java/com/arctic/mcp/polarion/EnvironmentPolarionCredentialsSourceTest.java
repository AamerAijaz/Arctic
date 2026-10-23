package com.arctic.mcp.polarion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentPolarionCredentialsSourceTest {

  @Test
  void dotenvFillsMissingVariablesAndNormalizesUrl() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");
    Files.writeString(
        workingDirectory.resolve(".env"),
        "POLARION_URL=https://from-dotenv.example\nPOLARION_TOKEN=from-dotenv\n");

    PolarionCredentials credentials =
        new EnvironmentPolarionCredentialsSource(Map.of(), workingDirectory).get();

    assertEquals("https://from-dotenv.example/polarion/rest/v1", credentials.restRoot());
    assertEquals("from-dotenv", credentials.token());
  }

  @Test
  void environmentVariableWinsOverDotenv() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");
    Files.writeString(
        workingDirectory.resolve(".env"),
        "POLARION_URL=https://from-dotenv.example\nPOLARION_TOKEN=from-dotenv\n");

    PolarionCredentials credentials =
        new EnvironmentPolarionCredentialsSource(
                Map.of(
                    "POLARION_URL", "https://from-env.example/polarion",
                    "POLARION_TOKEN", "from-env"),
                workingDirectory)
            .get();

    assertEquals("https://from-env.example/polarion/rest/v1", credentials.restRoot());
    assertEquals("from-env", credentials.token());
  }

  @Test
  void missingUrlOrTokenTellsTheOperatorWhatToSet() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");

    MissingCredentialsException error =
        assertThrows(
            MissingCredentialsException.class,
            () -> new EnvironmentPolarionCredentialsSource(Map.of(), workingDirectory).get());

    assertEquals(
        "Set POLARION_URL and POLARION_TOKEN. "
            + "Create the token in Polarion under My Account > Personal Access Tokens.",
        error.getMessage());
  }

  @Test
  void blankValueCountsAsMissing() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");

    assertThrows(
        MissingCredentialsException.class,
        () ->
            new EnvironmentPolarionCredentialsSource(
                    Map.of("POLARION_URL", "https://polarion.example.com", "POLARION_TOKEN", "   "),
                    workingDirectory)
                .get());
  }

  @Test
  void quotedDotenvValueIsUnwrapped() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");
    Files.writeString(
        workingDirectory.resolve(".env"),
        "POLARION_URL=\"https://quoted.example/polarion/\"\nPOLARION_TOKEN='quoted-token'\n");

    PolarionCredentials credentials =
        new EnvironmentPolarionCredentialsSource(Map.of(), workingDirectory).get();

    assertEquals("https://quoted.example/polarion/rest/v1", credentials.restRoot());
    assertEquals("quoted-token", credentials.token());
  }

  @Test
  void commonUrlFormsNormalizeToTheRestRoot() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");

    assertEquals(
        "https://polarion.example.com/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com").restRoot());
    assertEquals(
        "https://polarion.example.com/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/polarion/").restRoot());
    assertEquals(
        "https://polarion.example.com/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/polarion/rest/v1").restRoot());
    assertEquals(
        "https://polarion.example.com/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/rest/v1").restRoot());
    assertEquals(
        "https://polarion.example.com/custom/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/custom").restRoot());
    assertEquals(
        "https://polarion.example.com/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/polarion?x=1#wiki").restRoot());
    assertEquals(
        "https://polarion.example.com/polarion/rest/v1",
        credentials(workingDirectory, "https://polarion.example.com/polarion?x=1 2#wiki")
            .restRoot());
  }

  @Test
  void nonHttpUrlFailsBeforeARequest() throws Exception {
    Path workingDirectory = Files.createTempDirectory("arctic-env");

    MissingCredentialsException error =
        assertThrows(
            MissingCredentialsException.class,
            () ->
                new EnvironmentPolarionCredentialsSource(
                        Map.of("POLARION_URL", "polarion.example.com", "POLARION_TOKEN", "token"),
                        workingDirectory)
                    .get());

    assertEquals("POLARION_URL must be an http(s) URL of the Polarion server.", error.getMessage());
  }

  private static PolarionCredentials credentials(Path workingDirectory, String url) {
    return new EnvironmentPolarionCredentialsSource(
            Map.of("POLARION_URL", url, "POLARION_TOKEN", "token"), workingDirectory)
        .get();
  }
}
