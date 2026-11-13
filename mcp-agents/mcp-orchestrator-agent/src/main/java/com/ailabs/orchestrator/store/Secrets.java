package com.ailabs.orchestrator.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.ailabs.orchestrator.config.OrchestratorProperties;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Resolves secret references; values are never written to agents.yml or settings.yml and never sent to the UI.
 * <ul>
 *   <li>{@code store:<key>}: pasted in the UI, kept in {@code ~/.orchestrator/tokens.yml} (mode 600)</li>
 *   <li>{@code env:<NAME>}: environment variable</li>
 *   <li>{@code path:<file>}: file contents, trimmed ({@code ~/} allowed)</li>
 * </ul>
 */
@Component
public class Secrets {

    private final Path file;
    private final Map<String, String> store;

    public Secrets(OrchestratorProperties props) {
        this.file = props.homeDir().resolve("tokens.yml");
        this.store = new TreeMap<>(YamlFiles.read(file, new TypeReference<Map<String, String>>() {}, TreeMap::new));
    }

    /** @return the secret, or null when {@code ref} is blank */
    public String resolve(String ref) {
        if (ref == null || ref.isBlank()) return null;
        int i = ref.indexOf(':');
        String scheme = i < 0 ? "" : ref.substring(0, i);
        String rest = i < 0 ? ref : ref.substring(i + 1);
        return switch (scheme) {
            case "env" -> {
                String v = System.getenv(rest);
                if (v == null || v.isBlank()) throw new IllegalStateException("environment variable " + rest + " is not set");
                yield v.strip();
            }
            case "path" -> {
                Path p = Path.of(rest.startsWith("~/") ? System.getProperty("user.home") + rest.substring(1) : rest);
                try {
                    yield Files.readString(p).strip();
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read secret file " + p + ": " + e.getMessage());
                }
            }
            case "store" -> {
                String v;
                synchronized (this) {
                    v = store.get(rest);
                }
                if (v == null) throw new IllegalStateException("no stored secret named " + rest);
                yield v;
            }
            default -> throw new IllegalArgumentException("unknown secret reference '" + scheme
                    + ":'; use env:NAME, path:/file or store:key");
        };
    }

    /** Stores a pasted secret and returns its reference. */
    public synchronized String put(String key, String value) {
        store.put(key, value);
        save();
        return "store:" + key;
    }

    public synchronized void remove(String key) {
        if (store.remove(key) != null) save();
    }

    public synchronized void rename(String from, String to) {
        String v = store.remove(from);
        if (v != null) {
            store.put(to, v);
            save();
        }
    }

    private void save() {
        try {
            YamlFiles.write(file, store, true);
        } catch (UncheckedIOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public static void validateRef(String ref) {
        if (ref == null || ref.isBlank()) return;
        if (!(ref.startsWith("env:") || ref.startsWith("path:") || ref.startsWith("store:"))) {
            throw new IllegalArgumentException("secret reference must start with env:, path: or store:");
        }
    }
}
