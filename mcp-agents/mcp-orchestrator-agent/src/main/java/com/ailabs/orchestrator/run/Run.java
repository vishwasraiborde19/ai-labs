package com.ailabs.orchestrator.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** One piece of work (a mission, delegation, chain or tool call) and its timeline. Saved to {@code runs/<id>.json}. */
public class Run {

    public String id;
    /** orchestrate, plan, delegate, chain, tool */
    public String mode;
    public String title;
    public String input;
    public String agent;
    public String chainId;
    /** queued, running, waiting (for approval), succeeded, failed, cancelled, interrupted */
    public volatile String status = "queued";
    public Instant createdAt;
    public volatile Instant finishedAt;
    public volatile String result;
    public volatile String error;
    /** Mode-specific output, e.g. the proposed chain of a plan run. */
    public volatile Object data;
    public long inputTokens;
    public long outputTokens;
    public final List<RunEvent> events = new ArrayList<>();
    /** approval id -> what is waiting */
    public final Map<String, RunEvent> pendingApprovals = new LinkedHashMap<>();

    @JsonIgnore
    volatile boolean cancelRequested;

    public boolean finished() {
        return switch (status) {
            case "succeeded", "failed", "cancelled", "interrupted" -> true;
            default -> false;
        };
    }

    /** Copy for the API: events after {@code afterSeq} only. */
    public synchronized Map<String, Object> view(int afterSeq, boolean withEvents) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("mode", mode);
        m.put("title", title);
        m.put("input", input);
        m.put("agent", agent);
        m.put("chainId", chainId);
        m.put("status", status);
        m.put("createdAt", createdAt);
        m.put("finishedAt", finishedAt);
        m.put("result", result);
        m.put("error", error);
        m.put("data", data);
        m.put("inputTokens", inputTokens);
        m.put("outputTokens", outputTokens);
        m.put("eventCount", events.size());
        m.put("pendingApprovals", new ArrayList<>(pendingApprovals.values()));
        if (withEvents) m.put("events", events.stream().filter(e -> e.seq() > afterSeq).toList());
        return m;
    }
}
