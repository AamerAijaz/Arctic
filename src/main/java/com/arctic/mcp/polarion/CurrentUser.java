package com.arctic.mcp.polarion;

/** The Polarion user the personal access token acts as. Name and email may be absent. */
public record CurrentUser(String id, String name, String email) {}
