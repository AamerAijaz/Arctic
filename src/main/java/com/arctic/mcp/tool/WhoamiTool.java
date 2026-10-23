package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.CurrentUser;
import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import org.springframework.ai.tool.annotation.Tool;

/** whoami: the Polarion user for the configured personal access token. Takes no token. */
public final class WhoamiTool {

  static final String DESCRIPTION =
      """
      Return the Polarion user for the configured personal access token.

      Call this before creating or changing Polarion data so you know which
      user the action will run as. This tool does not take a token.\
      """;

  private final PolarionClient client;

  public WhoamiTool(PolarionClient client) {
    this.client = client;
  }

  @Tool(name = "whoami", description = DESCRIPTION)
  public CurrentUser whoami() {
    try {
      return client.getCurrentUser();
    } catch (PolarionException exception) {
      throw new RuntimeException(exception.getMessage());
    }
  }
}
