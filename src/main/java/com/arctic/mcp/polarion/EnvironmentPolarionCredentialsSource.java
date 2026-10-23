package com.arctic.mcp.polarion;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Polarion Credentials from the process environment. A {@code .env} file in the working directory
 * fills only variables that are not already set.
 */
public final class EnvironmentPolarionCredentialsSource implements PolarionCredentialsSource {

  static final String MISSING =
      "Set POLARION_URL and POLARION_TOKEN. "
          + "Create the token in Polarion under My Account > Personal Access Tokens.";

  static final String INVALID_URL = "POLARION_URL must be an http(s) URL of the Polarion server.";

  private final Map<String, String> environment;
  private final Path workingDirectory;

  public EnvironmentPolarionCredentialsSource(
      Map<String, String> environment, Path workingDirectory) {
    this.environment = environment;
    this.workingDirectory = workingDirectory;
  }

  @Override
  public PolarionCredentials get() {
    Map<String, String> values = new HashMap<>(environment);
    loadDotEnv(workingDirectory.resolve(".env"), values);
    String rawUrl = values.getOrDefault("POLARION_URL", "").trim();
    String token = values.getOrDefault("POLARION_TOKEN", "").trim();
    if (rawUrl.isEmpty() || token.isEmpty()) {
      throw new MissingCredentialsException(MISSING);
    }
    return new PolarionCredentials(normalizeRestRoot(rawUrl), token);
  }

  private static void loadDotEnv(Path envFile, Map<String, String> values) {
    if (!Files.isRegularFile(envFile)) {
      return;
    }
    try {
      for (String raw : Files.readAllLines(envFile)) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
          continue;
        }
        int split = line.indexOf('=');
        String key = line.substring(0, split).trim();
        String value = unquote(line.substring(split + 1));
        if (!key.isEmpty()) {
          values.putIfAbsent(key, value);
        }
      }
    } catch (IOException exception) {
      throw new PolarionException("Could not read .env.");
    }
  }

  private static String unquote(String value) {
    String stripped = value.trim();
    stripped = stripSurrounding(stripped, '"');
    return stripSurrounding(stripped, '\'');
  }

  private static String stripSurrounding(String value, char quote) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == quote) {
      start++;
    }
    while (end > start && value.charAt(end - 1) == quote) {
      end--;
    }
    return value.substring(start, end);
  }

  static String normalizeRestRoot(String url) {
    String withoutQuery = url.trim();
    int fragment = withoutQuery.indexOf('#');
    if (fragment >= 0) {
      withoutQuery = withoutQuery.substring(0, fragment);
    }
    int query = withoutQuery.indexOf('?');
    if (query >= 0) {
      withoutQuery = withoutQuery.substring(0, query);
    }
    URI parsed;
    try {
      parsed = new URI(withoutQuery);
    } catch (URISyntaxException exception) {
      throw new MissingCredentialsException(INVALID_URL);
    }
    String scheme = parsed.getScheme();
    String authority = parsed.getAuthority();
    if ((!"http".equals(scheme) && !"https".equals(scheme))
        || authority == null
        || authority.isEmpty()) {
      throw new MissingCredentialsException(INVALID_URL);
    }
    String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String restPath;
    if (path.endsWith("/polarion/rest/v1")) {
      restPath = path;
    } else if (path.endsWith("/polarion")) {
      restPath = path + "/rest/v1";
    } else if (path.endsWith("/rest/v1")) {
      restPath = path;
    } else if (path.isEmpty()) {
      restPath = "/polarion/rest/v1";
    } else {
      restPath = path + "/polarion/rest/v1";
    }
    return scheme + "://" + authority + restPath;
  }
}
