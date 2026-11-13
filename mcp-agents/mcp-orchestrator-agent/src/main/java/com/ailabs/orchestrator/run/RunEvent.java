package com.ailabs.orchestrator.run;

import java.time.Instant;

/**
 * One line of a run's timeline.
 *
 * @param type     start, plan, delegate, say, tool, tool-result, tool-error, answer, step, approval,
 *                 approval-result, done, error
 * @param agent    agent id, or null for the orchestrator itself
 * @param approval approval id for {@code approval} events
 */
public record RunEvent(int seq, Instant ts, String type, String agent, String title, String detail, String approval) {}
