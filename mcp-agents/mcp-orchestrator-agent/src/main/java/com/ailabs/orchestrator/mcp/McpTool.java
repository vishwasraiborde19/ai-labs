package com.ailabs.orchestrator.mcp;

import java.util.Map;

/** A tool as listed by an MCP server. */
public record McpTool(String name, String title, String description, Map<String, Object> inputSchema,
                      Map<String, Object> annotations) {}
