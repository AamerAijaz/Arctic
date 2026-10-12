package dev.arctic.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@SpringBootApplication
public class ArcticApplication {

    static final String VERSION = readVersion();
    static final String TOKEN_ENV = "ARCTIC_SERVE_TOKEN";

    private static final String USAGE = """
            usage: arctic stdio
                   arctic serve --port <n> [--parent-pid <pid>]""";

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            if (e.getMessage() != null) {
                System.err.println("error: " + e.getMessage());
            }
            System.err.println(USAGE);
            System.exit(2);
        }
    }

    private static void run(String[] args) {
        String mode = args.length > 0 ? args[0] : "";
        String[] options = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        switch (mode) {
            case "stdio" -> {
                if (options.length > 0) {
                    throw new UsageException("stdio takes no options");
                }
                stdioApplication().run();
            }
            case "serve" -> serve(options);
            default -> throw new UsageException(null);
        }
    }

    private static void serve(String[] options) {
        Integer port = null;
        Long parentPid = null;
        for (int i = 0; i < options.length; i += 2) {
            if (i + 1 >= options.length) {
                throw new UsageException("missing value for " + options[i]);
            }
            String value = options[i + 1];
            switch (options[i]) {
                case "--port" -> port = parsePort(value);
                case "--parent-pid" -> parentPid = parseLong("--parent-pid", value);
                default -> throw new UsageException("unknown option " + options[i]);
            }
        }
        if (port == null) {
            throw new UsageException("--port is required");
        }
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            System.err.println("error: environment variable " + TOKEN_ENV + " must be set to a non-empty token");
            System.exit(1);
        }
        serveApplication(port, token).run();
        if (parentPid != null) {
            exitWhenProcessEnds(parentPid);
        }
    }

    static SpringApplication stdioApplication() {
        return application(WebApplicationType.NONE, Map.of(
                "spring.ai.mcp.server.stdio", "true",
                "spring.ai.mcp.server.name", "arctic",
                "spring.ai.mcp.server.version", VERSION));
    }

    static SpringApplication serveApplication(int port, String token) {
        return application(WebApplicationType.SERVLET, Map.of(
                "server.address", "127.0.0.1",
                "server.port", Integer.toString(port),
                "spring.ai.mcp.server.enabled", "false",
                "arctic.serve.token", token));
    }

    private static SpringApplication application(WebApplicationType type, Map<String, Object> properties) {
        Map<String, Object> forced = new HashMap<>(properties);
        forced.put("spring.main.web-application-type", type.name().toLowerCase(Locale.ROOT));
        forced.put("arctic.version", VERSION);
        var environment = new StandardEnvironment();
        // Highest precedence: config files, env vars or system properties must not be able to switch transports.
        environment.getPropertySources().addFirst(new MapPropertySource("arcticMode", forced));

        var app = new SpringApplication(ArcticApplication.class);
        app.setWebApplicationType(type);
        app.setBannerMode(Banner.Mode.OFF);
        app.setEnvironment(environment);
        return app;
    }

    private static void exitWhenProcessEnds(long pid) {
        Logger log = LoggerFactory.getLogger(ArcticApplication.class);
        ProcessHandle.of(pid).ifPresentOrElse(
                parent -> parent.onExit().thenRun(() -> {
                    log.info("Parent process {} exited; shutting down", pid);
                    System.exit(0);
                }),
                () -> {
                    log.error("Parent process {} is not running; shutting down", pid);
                    System.exit(1);
                });
    }

    private static int parsePort(String value) {
        long port = parseLong("--port", value);
        if (port < 1 || port > 65535) {
            throw new UsageException("--port must be between 1 and 65535");
        }
        return (int) port;
    }

    private static long parseLong(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be a number");
        }
    }

    private static String readVersion() {
        var properties = new Properties();
        try (InputStream in = ArcticApplication.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("build.version", "unknown");
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
