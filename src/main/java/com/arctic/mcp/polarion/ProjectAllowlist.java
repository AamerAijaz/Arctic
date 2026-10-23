package com.arctic.mcp.polarion;

import java.util.LinkedHashSet;
import java.util.Set;

/** Project ids from POLARION_PROJECT_ALLOWLIST. Empty means every project is allowed. */
public final class ProjectAllowlist {

  private final Set<String> ids;

  private ProjectAllowlist(Set<String> ids) {
    this.ids = ids;
  }

  public static ProjectAllowlist unrestricted() {
    return new ProjectAllowlist(Set.of());
  }

  public static ProjectAllowlist fromEnvironment() {
    return parse(System.getenv("POLARION_PROJECT_ALLOWLIST"));
  }

  public static ProjectAllowlist parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return unrestricted();
    }
    LinkedHashSet<String> parsed = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      String id = part.trim();
      if (!id.isEmpty()) {
        parsed.add(id);
      }
    }
    return new ProjectAllowlist(Set.copyOf(parsed));
  }

  public boolean restricts() {
    return !ids.isEmpty();
  }

  public boolean allows(String projectId) {
    return ids.isEmpty() || ids.contains(projectId);
  }

  public void assertAllowed(String projectId) {
    if (!allows(projectId)) {
      throw new PolarionApiException(
          403, "Project " + projectId + " is not in POLARION_PROJECT_ALLOWLIST.");
    }
  }
}
