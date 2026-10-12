package dev.arctic.polarion;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Optional set of project ids the client may touch. Empty means every project is allowed. */
public final class ProjectAllowlist {

    public static final ProjectAllowlist ALL = new ProjectAllowlist(Set.of());

    private final Set<String> projects;

    public ProjectAllowlist(Collection<String> projects) {
        this.projects = projects.stream()
                .map(String::strip)
                .filter(project -> !project.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Parses the comma-separated {@code POLARION_PROJECT_ALLOWLIST} format; blank entries are ignored. */
    public static ProjectAllowlist parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return ALL;
        }
        return new ProjectAllowlist(Arrays.asList(commaSeparated.split(",")));
    }

    public Set<String> projects() {
        return projects;
    }

    public boolean allows(String projectId) {
        return projects.isEmpty() || projects.contains(projectId);
    }

    /** @throws PolarionApiException with status 403 if the project is not allowed */
    public void check(String projectId) {
        if (!allows(projectId)) {
            throw new PolarionApiException(403, "Project " + projectId + " is not in POLARION_PROJECT_ALLOWLIST.");
        }
    }

    public <T> List<T> filter(List<T> items, Function<T, String> projectId) {
        return items.stream().filter(item -> allows(projectId.apply(item))).toList();
    }
}
