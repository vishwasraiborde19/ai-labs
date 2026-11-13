package com.ailabs.orchestrator.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ailabs.orchestrator.mcp.McpServerInfo;
import com.ailabs.orchestrator.mcp.McpTool;

/** Live state of one agent: connection status, tool catalogue, what it is doing now. */
public class AgentState {

    public volatile String status = "unknown";      // unknown, online, offline, disabled
    public volatile String error;
    public volatile McpServerInfo server;
    public volatile List<McpTool> tools = List.of();
    public volatile Instant lastCheck;
    public volatile Instant lastActive;
    public volatile String toolsChanged;            // short note when the catalogue changed at the last refresh
    public final AtomicInteger busy = new AtomicInteger();
    public volatile String currentTask;
    public final AtomicLong calls = new AtomicLong();
    public final AtomicLong failures = new AtomicLong();
    public final AtomicLong tasks = new AtomicLong();

    synchronized void online(McpServerInfo info, List<McpTool> newTools) {
        if (!tools.isEmpty() || "online".equals(status)) {
            List<String> before = tools.stream().map(McpTool::name).toList();
            List<String> after = newTools.stream().map(McpTool::name).toList();
            List<String> added = new ArrayList<>(after);
            added.removeAll(before);
            List<String> removed = new ArrayList<>(before);
            removed.removeAll(after);
            if (!added.isEmpty() || !removed.isEmpty()) {
                toolsChanged = (added.isEmpty() ? "" : "added " + String.join(", ", added))
                        + (!added.isEmpty() && !removed.isEmpty() ? "; " : "")
                        + (removed.isEmpty() ? "" : "removed " + String.join(", ", removed));
            }
        }
        server = info;
        tools = List.copyOf(newTools);
        status = "online";
        error = null;
        lastCheck = Instant.now();
    }

    synchronized void offline(String why) {
        status = "offline";
        error = why;
        lastCheck = Instant.now();
    }
}
