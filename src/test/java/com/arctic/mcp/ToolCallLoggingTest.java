package com.arctic.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCallLoggingTest {

  private static final String TOKEN = "super-secret-token";

  @Test
  void successLogNamesTheToolAndOmitsTheInput() {
    ToolCallback tool = logged(input -> "{\"id\":\"alice\"}");

    String log =
        capture(
            () -> {
              String body = tool.call("{\"note\":\"" + TOKEN + "\"}");
              assertEquals("{\"id\":\"alice\"}", body);
            });

    assertTrue(log.contains("tool whoami succeeded in "));
    assertTrue(log.contains(" ms"));
    assertFalse(log.contains(TOKEN));
    assertFalse(log.contains("alice"));
  }

  @Test
  void failureLogNamesTheToolAndOmitsTheInput() {
    ToolCallback tool =
        logged(
            input -> {
              throw new RuntimeException("Polarion rejected the token");
            });

    String log =
        capture(
            () -> {
              RuntimeException error = assertThrows(RuntimeException.class, () -> tool.call(TOKEN));
              assertEquals("Polarion rejected the token", error.getMessage());
            });

    assertTrue(log.contains("tool whoami failed in "));
    assertFalse(log.contains(TOKEN));
  }

  private static ToolCallback logged(Function<String, String> behavior) {
    ToolCallback delegate =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("whoami")
                .description("who")
                .inputSchema("{}")
                .build();
          }

          @Override
          public String call(String toolInput) {
            return behavior.apply(toolInput);
          }
        };
    return new ToolCallLogging(() -> new ToolCallback[] {delegate}).getToolCallbacks()[0];
  }

  private static String capture(Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(ToolCallLogging.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      action.run();
      StringBuilder text = new StringBuilder();
      for (ILoggingEvent event : appender.list) {
        text.append(event.getFormattedMessage()).append('\n');
      }
      return text.toString();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
  }
}
