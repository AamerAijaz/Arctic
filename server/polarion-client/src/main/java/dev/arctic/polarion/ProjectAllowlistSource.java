package dev.arctic.polarion;

/** Current project allowlist. Called on every operation so a config change applies without a restart. */
@FunctionalInterface
public interface ProjectAllowlistSource {

    ProjectAllowlist current();

    static ProjectAllowlistSource allowAll() {
        return () -> ProjectAllowlist.ALL;
    }
}
