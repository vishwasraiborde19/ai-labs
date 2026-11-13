package com.ailabs.orchestrator.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Any OpenAI-compatible {@code /chat/completions} endpoint (Ollama, vLLM, LM Studio, LiteLLM...), for running
 * agents on a local model. Tool calling quality depends on the model.
 */
final class OpenAiCompatibleProvider implements Llm.Provider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final LlmSettings.ProviderConfig cfg;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    OpenAiCompatibleProvider(LlmSettings.ProviderConfig cfg, String apiKey) {
        this.cfg = cfg;
        this.apiKey = apiKey;
    }

    @Override
    public Llm.Result run(Llm.Request req) {
        String model = req.model() != null && !req.model().isBlank() ? req.model() : cfg.model;
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "system").put("content", req.system());
        messages.addObject().put("role", "user").put("content", req.user());
        ArrayNode tools = JSON.createArrayNode();
        Map<String, String> toMcp = new HashMap<>();
        for (Llm.Tool t : req.tools()) {
            String safe = t.name().replaceAll("[^a-zA-Z0-9_-]", "_");
            toMcp.put(safe, t.name());
            ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
            fn.put("name", safe);
            fn.put("description", t.description() == null ? "" : t.description());
            fn.set("parameters", JSON.valueToTree(t.schema() == null ? Map.of("type", "object") : t.schema()));
        }

        String lastText = "";
        for (int turn = 1; turn <= req.maxTurns(); turn++) {
            req.listener().checkCancelled();
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            if (!tools.isEmpty()) body.set("tools", tools);
            if (cfg.maxTokens != null) body.put("max_tokens", cfg.maxTokens);
            JsonNode resp = post(body);
            JsonNode usage = resp.path("usage");
            req.listener().usage(usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
            JsonNode choice = resp.path("choices").path(0);
            JsonNode msg = choice.path("message");
            if (msg.isMissingNode()) throw new Llm.LlmException("unexpected response: " + resp.toString());
            messages.add(msg);
            String text = msg.path("content").asText("");
            if (!text.isBlank()) {
                lastText = text;
                req.listener().say(text);
            }
            JsonNode calls = msg.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                for (JsonNode call : calls) {
                    req.listener().checkCancelled();
                    String name = call.path("function").path("name").asText();
                    Map<String, Object> args;
                    try {
                        String raw = call.path("function").path("arguments").asText("{}");
                        args = raw.isBlank() ? Map.of() : JSON.readValue(raw, MAP);
                    } catch (IOException e) {
                        args = null;
                    }
                    Llm.Outcome out = args == null ? Llm.Outcome.error("arguments were not valid JSON")
                            : req.handler().call(toMcp.getOrDefault(name, name), args);
                    messages.addObject().put("role", "tool").put("tool_call_id", call.path("id").asText())
                            .put("content", (out.error() ? "ERROR: " : "") + out.text());
                }
                continue;
            }
            return new Llm.Result(lastText, turn, choice.path("finish_reason").asText(""), model);
        }
        return new Llm.Result(lastText + "\n\n[stopped after " + req.maxTurns() + " turns]", req.maxTurns(), "max_turns", model);
    }

    private JsonNode post(ObjectNode body) {
        String base = cfg.baseUrl.endsWith("/") ? cfg.baseUrl.substring(0, cfg.baseUrl.length() - 1) : cfg.baseUrl;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> r = http.send(b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                String s = r.body();
                throw new Llm.LlmException("LLM HTTP " + r.statusCode() + ": " + (s.length() > 400 ? s.substring(0, 400) : s));
            }
            return JSON.readTree(r.body());
        } catch (IOException e) {
            throw new Llm.LlmException("cannot reach " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Llm.LlmException("interrupted");
        }
    }
}
