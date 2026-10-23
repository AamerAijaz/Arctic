package com.arctic.mcp.polarion;

/** Error safe to show to an operator or a model. The message never includes the token. */
public class PolarionException extends RuntimeException {

  public PolarionException(String message) {
    super(message);
  }
}
