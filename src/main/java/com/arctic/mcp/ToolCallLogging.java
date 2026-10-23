package com.arctic.mcp;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Logs each tool name, outcome, and duration. Arguments and results stay out of the log. */
final class ToolCallLogging implements ToolCallbackProvider {

  private static final Logger log = LoggerFactory.getLogger(ToolCallLogging.class);

  private final ToolCallback[] tools;

  ToolCallLogging(ToolCallbackProvider delegate) {
    this.tools =
        Arrays.stream(delegate.getToolCallbacks())
            .map(LoggedTool::new)
            .toArray(ToolCallback[]::new);
  }

  @Override
  public ToolCallback[] getToolCallbacks() {
    return tools.clone();
  }

  private static final class LoggedTool implements ToolCallback {

    private final ToolCallback delegate;

    private LoggedTool(ToolCallback delegate) {
      this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
      long started = System.nanoTime();
      try {
        String result = delegate.call(toolInput);
        log.info("tool {} succeeded in {} ms", name(), elapsedMillis(started));
        return result;
      } catch (RuntimeException exception) {
        log.info("tool {} failed in {} ms", name(), elapsedMillis(started));
        throw exception;
      }
    }

    private String name() {
      return delegate.getToolDefinition().name();
    }

    private static long elapsedMillis(long started) {
      return (System.nanoTime() - started) / 1_000_000L;
    }
  }
}
