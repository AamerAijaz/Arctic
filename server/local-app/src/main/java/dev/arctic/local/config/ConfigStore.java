package dev.arctic.local.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes {@code config.json} in the data directory. A missing file means Arctic is not configured. */
public final class ConfigStore {

    static final String FILE_NAME = "config.json";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private final Path file;

    public ConfigStore(Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    public Optional<ArcticConfig> read() {
        try {
            return Optional.of(JSON.readValue(Files.readString(file), ArcticConfig.class));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the file atomically so a concurrent reader never sees a partial write. */
    public void write(ArcticConfig config) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            try {
                Files.writeString(temp, JSON.writeValueAsString(config));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
