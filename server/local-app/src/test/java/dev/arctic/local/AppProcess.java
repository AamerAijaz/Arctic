package dev.arctic.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.arctic.local.config.DataDirectory;

/**
 * Launches the boot jar in a separate JVM on the Java toolchain, as configured by the Gradle test task.
 */
final class AppProcess {

    private AppProcess() {
    }

    static String java() {
        return requiredProperty("arctic.test.java");
    }

    /**
     * Starts the app; stderr is drained into {@code stderr.log} under {@code dir}. The data directory is
     * {@code dir/home} and inherited Arctic and Polarion variables are removed, unless {@code env} sets them.
     */
    static Process start(Path dir, Map<String, String> env, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(java(), "-jar", requiredProperty("arctic.test.bootJar")));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectError(dir.resolve("stderr.log").toFile());
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("ARCTIC_")
                || name.toUpperCase(Locale.ROOT).startsWith("POLARION_"));
        builder.environment().put(DataDirectory.HOME_ENV, dir.resolve("home").toString());
        builder.environment().putAll(env);
        return builder.start();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property " + name + " is not set; run tests through Gradle");
        }
        return value;
    }

    /** Reads lines on a background thread so they can be consumed with a timeout. */
    static final class Lines {

        private static final String EOF = new String("<eof>");

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        Lines(InputStream in) {
            Thread.ofPlatform().daemon().name("app-stdout").start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        queue.add(line);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    queue.add(EOF);
                }
            });
        }

        /** Next line, or empty at end of stream. Fails if nothing arrives within {@code timeout}. */
        Optional<String> next(Duration timeout) throws InterruptedException {
            String line = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) {
                throw new AssertionError("no stdout line within " + timeout);
            }
            if (line == EOF) {
                queue.add(EOF);
                return Optional.empty();
            }
            return Optional.of(line);
        }
    }
}
