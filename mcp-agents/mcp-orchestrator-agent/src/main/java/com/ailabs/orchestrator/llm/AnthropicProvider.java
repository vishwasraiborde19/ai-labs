package com.ailabs.orchestrator.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

/**
 * Claude through the official Anthropic Java SDK: a manual tool loop over the Messages API. Each response is
 * appended to the history as returned (thinking blocks included), and all tool results of a turn go back in one
 * user message.
 */
final class AnthropicProvider implements Llm.Provider {

    /** Models that accept the server-side refusal fallback ({@code fallbacks: "default"}). */
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private final AnthropicClient client;
    private final LlmSettings.ProviderConfig cfg;

    AnthropicProvider(LlmSettings.ProviderConfig cfg, String apiKey) {
        this.cfg = cfg;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv();
        if (apiKey != null && !apiKey.isBlank()) b.apiKey(apiKey);
        if (cfg.baseUrl != null && !cfg.baseUrl.isBlank()) b.baseUrl(cfg.baseUrl);
        b.timeout(Duration.ofMinutes(10));
        this.client = b.build();
    }

    @Override
    public Llm.Result run(Llm.Request req) {
        String model = req.model() != null && !req.model().isBlank() ? req.model() : cfg.model;
        String effort = req.effort() != null && !req.effort().isBlank() ? req.effort() : cfg.effort;
        long maxTokens = cfg.maxTokens == null ? 16000L : cfg.maxTokens;

        // Claude tool names must match ^[a-zA-Z0-9_-]{1,128}$; map back to the MCP name on the way in.
        Map<String, String> toMcp = new HashMap<>();
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(req.system())
                .addUserMessage(req.user());
        for (Llm.Tool t : req.tools()) {
            String safe = safeName(t.name(), toMcp);
            toMcp.put(safe, t.name());
            b.addTool(toTool(safe, t));
        }
        if (effort != null && !effort.isBlank()) {
            b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build());
        }
        if (!Boolean.FALSE.equals(cfg.fallbacks) && FALLBACK_MODELS.contains(model)) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        String lastText = "";
        for (int turn = 1; turn <= req.maxTurns(); turn++) {
            req.listener().checkCancelled();
            Message resp;
            try {
                resp = client.messages().create(b.build());
            } catch (AnthropicServiceException e) {
                throw new Llm.LlmException("Claude API error (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new Llm.LlmException("Claude API call failed: " + e.getMessage(), e);
            }
            req.listener().usage(resp.usage().inputTokens(), resp.usage().outputTokens());
            b.addMessage(resp.toParam());

            StringBuilder text = new StringBuilder();
            List<ToolUseBlock> uses = new ArrayList<>();
            for (ContentBlock block : resp.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
                block.toolUse().ifPresent(uses::add);
            }
            if (!text.isEmpty()) {
                lastText = text.toString();
                req.listener().say(lastText);
            }
            String stop = resp.stopReason().map(s -> s.asString()).orElse("");

            if ("tool_use".equals(stop) && !uses.isEmpty()) {
                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock use : uses) {
                    req.listener().checkCancelled();
                    Llm.Outcome out;
                    try {
                        out = req.handler().call(toMcp.getOrDefault(use.name(), use.name()), input(use));
                    } catch (Llm.LlmException | IllegalArgumentException | IllegalStateException e) {
                        out = Llm.Outcome.error(e.getMessage());
                    }
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(out.text() == null || out.text().isEmpty() ? "(no output)" : out.text())
                            .isError(out.error())
                            .build()));
                }
                b.addUserMessageOfContentBlockParams(results);
                continue;
            }
            if ("refusal".equals(stop)) {
                return new Llm.Result(join(lastText, "[Claude declined this request]"), turn, stop, model);
            }
            if ("max_tokens".equals(stop)) {
                return new Llm.Result(join(lastText, "[stopped: the response hit the output token limit]"), turn, stop, model);
            }
            return new Llm.Result(lastText, turn, stop, model);
        }
        return new Llm.Result(join(lastText, "[stopped after " + req.maxTurns() + " turns]"), req.maxTurns(), "max_turns", model);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(ToolUseBlock use) {
        Object v = use._input().convert(Map.class);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Tool toTool(String name, Llm.Tool t) {
        Map<String, Object> schema = t.schema() == null ? Map.of() : t.schema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> p) {
            ((Map<String, Object>) p).forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        }
        Tool.InputSchema.Builder sb = Tool.InputSchema.builder().properties(props.build());
        if (schema.get("required") instanceof List<?> r && !r.isEmpty()) {
            sb.required(r.stream().map(o -> String.valueOf(o)).toList());
        }
        schema.forEach((k, v) -> {
            if (!Set.of("type", "properties", "required", "$schema").contains(k)) sb.putAdditionalProperty(k, JsonValue.from(v));
        });
        String desc = t.description() == null || t.description().isBlank() ? t.name() : t.description();
        return Tool.builder().name(name).description(desc).inputSchema(sb.build()).build();
    }

    private static String safeName(String name, Map<String, String> taken) {
        String s = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (s.length() > 120) s = s.substring(0, 120);
        String base = s;
        for (int i = 2; taken.containsKey(s); i++) s = base + "_" + i;
        return s;
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + "\n\n" + b;
    }
}
