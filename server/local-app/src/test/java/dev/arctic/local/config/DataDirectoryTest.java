package dev.arctic.local.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DataDirectoryTest {

    @Test
    void defaultsToLocalAppData() {
        assertThat(DataDirectory.resolve(Map.of("LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local")))
                .isEqualTo(Path.of("C:\\Users\\me\\AppData\\Local", "Arctic"));
    }

    @Test
    void arcticHomeOverrides() {
        assertThat(DataDirectory.resolve(Map.of("LOCALAPPDATA", "C:\\x", "ARCTIC_HOME", "D:\\arctic-data")))
                .isEqualTo(Path.of("D:\\arctic-data"));
    }

    @Test
    void blankArcticHomeIsIgnored() {
        assertThat(DataDirectory.resolve(Map.of("LOCALAPPDATA", "C:\\x", "ARCTIC_HOME", " ")))
                .isEqualTo(Path.of("C:\\x", "Arctic"));
    }
}
