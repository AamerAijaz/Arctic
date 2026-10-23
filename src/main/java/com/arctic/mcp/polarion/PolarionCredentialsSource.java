package com.arctic.mcp.polarion;

/** Supplies Polarion Credentials. The Polarion Client does not know where they come from. */
public interface PolarionCredentialsSource {

  PolarionCredentials get();
}
