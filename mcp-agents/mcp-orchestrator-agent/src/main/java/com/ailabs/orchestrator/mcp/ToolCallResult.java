package com.ailabs.orchestrator.mcp;

/** Text of a tools/call result; {@code error} is the server's isError flag. */
public record ToolCallResult(String text, boolean error) {}
