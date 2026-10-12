package dev.arctic.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

class StdioModeContextTest {

    @Test
    void stdioModeStartsNoWebServerAndWritesNothingToStdout() throws Exception {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        var stdout = new ByteArrayOutputStream();
        try (var stdinWriter = new PipedOutputStream(); var stdin = new PipedInputStream(stdinWriter)) {
            // The stdio transport binds System.in/System.out when it is created.
            System.setIn(stdin);
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            try (ConfigurableApplicationContext context = ArcticApplication.stdioApplication().run()) {
                assertThat(context).isNotInstanceOf(WebApplicationContext.class);
                assertThat(context.getEnvironment().getProperty("local.server.port")).isNull();
                assertThat(context.getEnvironment().getProperty("spring.ai.mcp.server.stdio")).isEqualTo("true");
            }
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
