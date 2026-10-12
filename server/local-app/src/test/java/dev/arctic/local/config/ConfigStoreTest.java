package dev.arctic.local.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ConfigStoreTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void missingFileMeansUnconfigured(@TempDir Path dir) {
        assertThat(new ConfigStore(dir.resolve("absent")).read()).isEmpty();
    }

    @Test
    void writesTheDocumentedShape(@TempDir Path dir) throws Exception {
        var store = new ConfigStore(dir.resolve("Arctic"));
        store.write(new ArcticConfig("https://polarion.example.com", List.of("ELK", "MOOSE"),
                new ArcticConfig.Model("http://localhost:11434/v1", "qwen3")));

        assertThat(store.file()).isEqualTo(dir.resolve("Arctic").resolve("config.json"));
        assertThat(json.readTree(Files.readString(store.file()))).isEqualTo(json.readTree("""
                {"polarionUrl": "https://polarion.example.com",
                 "projectAllowlist": ["ELK", "MOOSE"],
                 "model": {"baseUrl": "http://localhost:11434/v1", "model": "qwen3"}}"""));
        try (var files = Files.list(store.file().getParent())) {
            assertThat(files).containsExactly(store.file());
        }
    }

    @Test
    void roundTripsAndOverwrites(@TempDir Path dir) {
        var store = new ConfigStore(dir);
        var first = new ArcticConfig("https://a.example", List.of("ELK"), new ArcticConfig.Model("http://m", "x"));
        store.write(first);
        assertThat(store.read()).contains(first);

        var second = new ArcticConfig("https://b.example", List.of(), null);
        store.write(second);
        assertThat(store.read()).contains(second);
    }

    @Test
    void readsPartialFilesAndIgnoresUnknownFields(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.json"), """
                {"polarionUrl": "https://polarion.example.com", "futureSetting": true}""");

        assertThat(new ConfigStore(dir).read())
                .contains(new ArcticConfig("https://polarion.example.com", List.of(), null));
    }
}
