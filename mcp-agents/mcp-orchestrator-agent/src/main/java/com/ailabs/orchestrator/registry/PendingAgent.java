package com.ailabs.orchestrator.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An agent that registered itself or was discovered, waiting in the inbox. It gets no tasks until a person
 * approves it: its tool descriptions end up in LLM prompts, so an unknown server must not join silently.
 */
public class PendingAgent {
    public AgentSpec spec;
    public String from;
    public Instant receivedAt;
    public String serverName;
    public String serverVersion;
    public List<String> tools = new ArrayList<>();
    public String probeError;
}
