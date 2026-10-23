package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionException;

final class ToolSupport {

  private ToolSupport() {}

  static RuntimeException failed(PolarionException exception) {
    return new RuntimeException(exception.getMessage());
  }

  static boolean dryRun(Boolean dryRun) {
    return dryRun == null || dryRun;
  }
}
