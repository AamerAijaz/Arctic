package com.arctic.mcp.polarion;

/** Polarion Credentials: the REST root and personal access token. Tools never see these. */
public record PolarionCredentials(String restRoot, String token) {}
