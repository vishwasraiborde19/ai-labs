package com.ailabs.orchestrator.mcp;

/** Transport or protocol failure talking to an MCP server (not a tool that reported isError). */
public class McpException extends RuntimeException {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
