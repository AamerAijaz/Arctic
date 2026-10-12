package dev.arctic.polarion.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shared fixtures in {@code <repo>/fixtures/polarion}; see {@code fixtures/README.md}. */
public final class Fixtures {

    /** Origin recorded in the fixtures; {@link FixtureServer} stands in for it. */
    public static final String BASE_URL = "https://polarion.example.com";
    public static final String TOKEN = "test-token";

    static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    /** {@code fixtures/polarion}, from the {@code arctic.fixtures} system property set by the Gradle test task. */
    public static Path root() {
        String dir = System.getProperty("arctic.fixtures");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("System property arctic.fixtures is not set; run tests through Gradle");
        }
        return Path.of(dir, "polarion");
    }

    /** Parses a file under {@link #root()}, for example {@code "redaction.json"}. */
    public static JsonNode json(String relativePath) {
        return read(root().resolve(relativePath));
    }

    /** One scenario, for example {@code "auth/whoami_success"}. */
    public static Scenario scenario(String name) {
        return Scenario.load(root().resolve(name + ".json"));
    }

    /** Every scenario in one area directory, sorted by name. */
    public static List<Scenario> scenarios(String area) {
        try (Stream<Path> files = Files.list(root().resolve(area))) {
            List<Scenario> scenarios = files
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(Scenario::load)
                    .toList();
            if (scenarios.isEmpty()) {
                throw new IllegalStateException("No scenarios in " + root().resolve(area));
            }
            return scenarios;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode read(Path file) {
        try {
            return JSON.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
