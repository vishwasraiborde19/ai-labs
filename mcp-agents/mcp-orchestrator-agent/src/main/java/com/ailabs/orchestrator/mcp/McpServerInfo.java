package com.ailabs.orchestrator.mcp;

/** What an MCP server said about itself in its initialize response. */
public record McpServerInfo(String name, String version, String protocolVersion, String instructions) {}
