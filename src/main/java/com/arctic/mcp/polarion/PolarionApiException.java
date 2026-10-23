package com.arctic.mcp.polarion;

public class PolarionApiException extends PolarionException {

  private final int statusCode;

  public PolarionApiException(int statusCode, String detail) {
    super("Polarion request failed (" + statusCode + "): " + detail);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
