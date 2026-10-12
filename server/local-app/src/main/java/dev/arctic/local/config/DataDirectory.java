package dev.arctic.local.config;

import java.nio.file.Path;
import java.util.Map;

/** Where Arctic keeps its files: {@code %LOCALAPPDATA%\Arctic}, or {@code ARCTIC_HOME} if set. */
public final class DataDirectory {

    public static final String HOME_ENV = "ARCTIC_HOME";

    private DataDirectory() {
    }

    public static Path resolve(Map<String, String> env) {
        String home = env.get(HOME_ENV);
        if (home != null && !home.isBlank()) {
            return Path.of(home.strip());
        }
        String localAppData = env.get("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("Arctic");
    }
}
