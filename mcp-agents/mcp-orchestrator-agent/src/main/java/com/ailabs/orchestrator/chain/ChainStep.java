package com.ailabs.orchestrator.chain;

/**
 * One step of a chain.
 * <ul>
 *   <li>{@code delegate}: {@code agent} works on {@code task} with its LLM and tools; its report is the output</li>
 *   <li>{@code tool}: call {@code tool} on {@code agent} with {@code args} (JSON object); the tool's text is the output</li>
 * </ul>
 * {@code task} and {@code args} may use {@code {{input}}}, {@code {{prev}}} and {@code {{stepN}}} (1-based).
 */
public class ChainStep {
    public String type = "delegate";
    public String agent;
    public String task;
    public String tool;
    public String args;
    /** Pause for a person to approve before this step starts. */
    public boolean approve;
    /** Keep going when this step fails (its output becomes the error text). */
    public boolean continueOnError;
    /** Why the step is there (the planner fills this in). */
    public String note;

    void validate(int n) {
        String where = "step " + n + ": ";
        if (agent == null || agent.isBlank()) throw new IllegalArgumentException(where + "choose an agent");
        switch (type == null ? "" : type) {
            case "delegate" -> {
                if (task == null || task.isBlank()) throw new IllegalArgumentException(where + "write the task");
            }
            case "tool" -> {
                if (tool == null || tool.isBlank()) throw new IllegalArgumentException(where + "choose a tool");
            }
            default -> throw new IllegalArgumentException(where + "type must be delegate or tool");
        }
    }
}
