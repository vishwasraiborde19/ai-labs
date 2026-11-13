package com.ailabs.commandcenter.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Built-in system kinds ({@code kinds.yml} on the classpath): icon, category, the links a system of
 * that kind usually has, and a usage guide for developers. Categories are the lanes of the
 * architecture view, in this order.
 */
@Component
public class KindRegistry {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(String id, String label, String description) {}

    /**
     * @param healthPath appended to the system's first link when no health URL is set
     * @param linkHints  links to suggest when adding a system of this kind ({@code type: label})
     * @param guide      markdown usage guide
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Kind(String id, String label, String category, String abbr, String color, String summary,
                       String healthPath, Map<String, String> linkHints, String guide) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record File(List<Category> categories, List<Kind> kinds) {}

    private final List<Category> categories;
    private final Map<String, Kind> kinds = new LinkedHashMap<>();

    public KindRegistry() {
        try (InputStream in = KindRegistry.class.getResourceAsStream("/kinds.yml")) {
            if (in == null) {
                throw new IllegalStateException("kinds.yml missing from the classpath");
            }
            File f = new ObjectMapper(new YAMLFactory()).readValue(in, File.class);
            this.categories = List.copyOf(f.categories());
            for (Kind k : f.kinds()) {
                if (categories.stream().noneMatch(c -> c.id().equals(k.category()))) {
                    throw new IllegalStateException("kind " + k.id() + " has unknown category " + k.category());
                }
                kinds.put(k.id(), k);
            }
            if (!kinds.containsKey("generic")) {
                throw new IllegalStateException("kinds.yml must define the generic kind");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read kinds.yml: " + e.getMessage(), e);
        }
    }

    public List<Category> categories() {
        return categories;
    }

    public List<Kind> kinds() {
        return List.copyOf(kinds.values());
    }

    public Kind kind(String id) {
        return kinds.getOrDefault(id, kinds.get("generic"));
    }

    public boolean known(String id) {
        return kinds.containsKey(id);
    }

    public boolean knownCategory(String id) {
        return categories.stream().anyMatch(c -> c.id().equals(id));
    }

    /** The system's category: its own, else its kind's. */
    public String categoryOf(Model.SystemEntry s) {
        return s.category() != null && knownCategory(s.category()) ? s.category() : kind(s.kind()).category();
    }
}
