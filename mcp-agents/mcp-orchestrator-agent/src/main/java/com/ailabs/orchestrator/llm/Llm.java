package com.ailabs.orchestrator.llm;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral contract for one agentic tool loop. Each provider owns its own message history so the
 * provider's native content (Claude's thinking blocks, for one) goes back to the API unchanged on every turn.
 */
public final class Llm {

    private Llm() {}

    /** A tool offered to the model; {@code schema} is a JSON Schema object (MCP inputSchema). */
    public record Tool(String name, String description, Map<String, Object> schema) {}

    public record Outcome(String text, boolean error) {
        public static Outcome ok(String text) {
            return new Outcome(text, false);
        }

        public static Outcome error(String text) {
            return new Outcome(text, true);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Outcome call(String tool, Map<String, Object> input);
    }

    /** Progress callbacks; {@code checkCancelled} throws to stop the loop between turns. */
    public interface Listener {
        void say(String text);

        default void usage(long inputTokens, long outputTokens) {}

        default void checkCancelled() {}
    }

    /**
     * @param model  overrides the provider's model when not blank
     * @param effort overrides the provider's effort when not blank (Claude: low..max)
     */
    public record Request(String system, String user, List<Tool> tools, Handler handler, Listener listener,
                          String model, String effort, int maxTurns) {}

    public record Result(String text, int turns, String stopReason, String model) {}

    public interface Provider {
        Result run(Request request);
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
