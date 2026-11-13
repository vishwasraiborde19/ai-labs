package com.ailabs.orchestrator.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code settings.yml}: LLM providers and the orchestrator's own instructions, edited in the UI. */
public class LlmSettings {

    public String defaultProvider = "claude";
    public Map<String, ProviderConfig> providers = new LinkedHashMap<>(Map.of("claude", ProviderConfig.claude()));
    /** The orchestrator's system prompt; blank uses {@link Prompts#ORCHESTRATOR}. */
    public String orchestratorInstructions;
    /** The planner's system prompt; blank uses {@link Prompts#PLANNER}. */
    public String plannerInstructions;
    /** Provider (and model) the orchestrator itself uses; blank: default provider. */
    public String orchestratorProvider;
    public String orchestratorModel;

    public static class ProviderConfig {
        /** {@code anthropic} or {@code openai} (any OpenAI-compatible chat completions API: Ollama, vLLM, LM Studio). */
        public String type = "anthropic";
        public String model;
        /** env:, path: or store:. Blank for anthropic: the SDK's own lookup (ANTHROPIC_API_KEY, ant auth profile). */
        public String apiKeyRef;
        public String baseUrl;
        /** Claude: low, medium, high, xhigh, max. */
        public String effort;
        /** Claude: server-side refusal fallback (on by default for models that support it). */
        public Boolean fallbacks;
        public Integer maxTokens;

        static ProviderConfig claude() {
            ProviderConfig c = new ProviderConfig();
            c.type = "anthropic";
            c.model = "claude-opus-5-5";
            c.effort = "high";
            c.fallbacks = true;
            c.maxTokens = 16000;
            return c;
        }

        void validate(String name) {
            if (!List.of("anthropic", "openai").contains(type)) {
                throw new IllegalArgumentException("provider " + name + ": type must be anthropic or openai");
            }
            if (model == null || model.isBlank()) throw new IllegalArgumentException("provider " + name + ": model is required");
            if ("openai".equals(type) && (baseUrl == null || baseUrl.isBlank())) {
                throw new IllegalArgumentException("provider " + name + ": base URL is required, e.g. http://127.0.0.1:11434/v1");
            }
            if (effort != null && !effort.isBlank() && !List.of("low", "medium", "high", "xhigh", "max").contains(effort)) {
                throw new IllegalArgumentException("provider " + name + ": effort must be low, medium, high, xhigh or max");
            }
        }
    }

    void validate() {
        if (providers == null || providers.isEmpty()) throw new IllegalArgumentException("add at least one LLM provider");
        providers.forEach((k, v) -> {
            if (!k.matches("[a-z0-9][a-z0-9-]{0,30}")) throw new IllegalArgumentException("provider names: lowercase letters, digits, dashes");
            v.validate(k);
        });
        if (defaultProvider == null || !providers.containsKey(defaultProvider)) {
            throw new IllegalArgumentException("default provider must be one of " + providers.keySet());
        }
        if (orchestratorProvider != null && !orchestratorProvider.isBlank() && !providers.containsKey(orchestratorProvider)) {
            throw new IllegalArgumentException("orchestrator provider must be one of " + providers.keySet());
        }
    }
}
