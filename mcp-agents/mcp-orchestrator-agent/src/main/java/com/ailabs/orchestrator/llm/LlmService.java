package com.ailabs.orchestrator.llm;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.ailabs.orchestrator.store.Audit;
import com.ailabs.orchestrator.store.Secrets;
import com.ailabs.orchestrator.store.YamlFiles;
import com.fasterxml.jackson.core.type.TypeReference;

/** Holds {@code settings.yml} and builds one provider client per configured LLM. */
@Service
public class LlmService {

    private final Secrets secrets;
    private final Path file;
    private volatile LlmSettings settings;
    private final Map<String, Llm.Provider> providers = new ConcurrentHashMap<>();

    public LlmService(OrchestratorProperties props, Secrets secrets) {
        this.secrets = secrets;
        this.file = props.homeDir().resolve("settings.yml");
        this.settings = YamlFiles.read(file, new TypeReference<LlmSettings>() {}, LlmSettings::new);
    }

    public LlmSettings settings() {
        return YamlFiles.YAML.convertValue(settings, LlmSettings.class);
    }

    /**
     * Saves settings. {@code apiKeys} maps provider name to a pasted key, stored as {@code store:llm.<name>}.
     */
    public synchronized LlmSettings save(LlmSettings s, Map<String, String> apiKeys) {
        if (s.providers == null) s.providers = new LinkedHashMap<>();
        if (apiKeys != null) {
            apiKeys.forEach((name, key) -> {
                LlmSettings.ProviderConfig c = s.providers.get(name);
                if (c != null && key != null && !key.isBlank()) c.apiKeyRef = secrets.put("llm." + name, key.strip());
            });
        }
        s.providers.values().forEach(c -> Secrets.validateRef(c.apiKeyRef));
        s.validate();
        YamlFiles.write(file, s, false);
        settings = s;
        providers.clear();
        Audit.log("ui", "SETTINGS", "providers=" + s.providers.keySet() + " default=" + s.defaultProvider);
        return settings();
    }

    public String resolveName(String name) {
        LlmSettings s = settings;
        return name == null || name.isBlank() || !s.providers.containsKey(name) ? s.defaultProvider : name;
    }

    public Llm.Provider provider(String name) {
        String n = resolveName(name);
        return providers.computeIfAbsent(n, k -> {
            LlmSettings.ProviderConfig c = settings.providers.get(k);
            if (c == null) throw new IllegalStateException("no LLM provider named " + k);
            String key = secrets.resolve(c.apiKeyRef);
            return "openai".equals(c.type) ? new OpenAiCompatibleProvider(c, key) : new AnthropicProvider(c, key);
        });
    }

    public String model(String providerName, String override) {
        if (override != null && !override.isBlank()) return override;
        LlmSettings.ProviderConfig c = settings.providers.get(resolveName(providerName));
        return c == null ? "?" : c.model;
    }

    /** One small request to prove the provider, key and model work. */
    public Map<String, Object> test(String name) {
        long t0 = System.currentTimeMillis();
        Llm.Result r = provider(name).run(new Llm.Request("You are a connectivity check.", "Reply with the single word OK.",
                List.of(), (tool, in) -> Llm.Outcome.error("no tools"), text -> {}, null, "low", 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", resolveName(name));
        out.put("model", r.model());
        out.put("reply", r.text());
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }
}
