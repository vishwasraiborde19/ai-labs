package com.ailabs.commandcenter.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ailabs.commandcenter.catalog.Model.Catalog;
import com.ailabs.commandcenter.catalog.Model.Dependency;
import com.ailabs.commandcenter.catalog.Model.Flow;
import com.ailabs.commandcenter.catalog.Model.FlowStep;
import com.ailabs.commandcenter.catalog.Model.Link;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;
import com.ailabs.commandcenter.config.CcProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * {@code catalog.yml}: every system and flow. Seeded from the classpath on first start, edited from
 * the UI, and re-read when someone edits the file by hand. Writes are atomic (temp file + move).
 */
@Component
public class CatalogStore {

    private static final Logger log = LoggerFactory.getLogger(CatalogStore.class);

    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE))
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /** An edit based on an old version of the catalog. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) {
            super(m);
        }
    }

    public record Snapshot(Catalog catalog, long version) {}

    private final Path file;
    private final KindRegistry kinds;
    private Catalog catalog;
    private long version;
    private FileTime loadedMtime;

    @Autowired
    public CatalogStore(KindRegistry kinds) {
        this(CcProperties.home().resolve("catalog.yml"), kinds);
    }

    public CatalogStore(Path file, KindRegistry kinds) {
        this.file = file;
        this.kinds = kinds;
        load();
    }

    public Path file() {
        return file;
    }

    private synchronized void load() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = CatalogStore.class.getResourceAsStream("/seed-catalog.yml")) {
                    Catalog seed = in == null ? new Catalog(null, null) : YAML.readValue(in, Catalog.class);
                    write(seed);
                    log.info("Created {} with {} systems from the built-in lab catalog", file, seed.systems().size());
                }
            }
            Catalog c = YAML.readValue(file.toFile(), Catalog.class);
            List<String> problems = problems(c);
            if (!problems.isEmpty()) {
                log.warn("{} has problems (kept as is, fix them in the UI or the file): {}", file, problems);
            }
            catalog = c;
            loadedMtime = Files.getLastModifiedTime(file);
            version++;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The current catalog, re-read first if the file was changed by hand. */
    public synchronized Snapshot snapshot() {
        try {
            if (Files.exists(file) && !Files.getLastModifiedTime(file).equals(loadedMtime)) {
                log.info("{} changed on disk, reloading", file);
                load();
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Keeping the loaded catalog: {}", e.getMessage());
        }
        return new Snapshot(catalog, version);
    }

    public Optional<SystemEntry> find(String id) {
        return snapshot().catalog().systems().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public SystemEntry require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no system '" + id + "'"));
    }

    // ------------------------------------------------------------------ systems

    /**
     * Adds a system ({@code originalId == null}) or replaces one, possibly renaming it; dependencies
     * and flow steps pointing at the old id follow the rename.
     */
    public synchronized SystemEntry saveSystem(SystemEntry s, String originalId, Long expectedVersion) {
        checkVersion(expectedVersion);
        Catalog c = snapshot().catalog();
        if (s.id() == null && s.name() != null) {
            s = s.withId(slug(s.name()));
        }
        String newId = s.id();
        boolean exists = c.systems().stream().anyMatch(x -> x.id().equals(newId));
        if (originalId == null && exists) {
            throw new ConflictException("a system with id '" + newId + "' already exists");
        }
        if (originalId != null && c.systems().stream().noneMatch(x -> x.id().equals(originalId))) {
            throw new IllegalArgumentException("no system '" + originalId + "'");
        }
        if (originalId != null && !originalId.equals(newId) && exists) {
            throw new ConflictException("a system with id '" + newId + "' already exists");
        }
        final SystemEntry entry = s;
        List<SystemEntry> systems = new ArrayList<>();
        boolean replaced = false;
        for (SystemEntry x : c.systems()) {
            if (x.id().equals(originalId)) {
                systems.add(entry);
                replaced = true;
            } else {
                systems.add(originalId != null && !originalId.equals(newId) ? renameDeps(x, originalId, newId) : x);
            }
        }
        if (!replaced) {
            systems.add(entry);
        }
        List<Flow> flows = originalId != null && !originalId.equals(newId)
                ? c.flows().stream().map(f -> renameSteps(f, originalId, newId)).toList() : c.flows();
        commit(new Catalog(systems, flows));
        return entry;
    }

    /** Removes a system, the dependencies on it and the flow steps through it. */
    public synchronized void deleteSystem(String id, Long expectedVersion) {
        checkVersion(expectedVersion);
        Catalog c = snapshot().catalog();
        require(id);
        List<SystemEntry> systems = c.systems().stream().filter(s -> !s.id().equals(id))
                .map(s -> s.withDependsOn(s.dependsOn().stream().filter(d -> !d.to().equals(id)).toList())).toList();
        List<Flow> flows = c.flows().stream().map(f -> new Flow(f.id(), f.name(), f.description(),
                f.steps().stream().filter(st -> !id.equals(st.from()) && !id.equals(st.to())).toList())).toList();
        commit(new Catalog(systems, flows));
    }

    /** Applies {@code change} to every system for which it returns a different value (used by integrations). */
    public synchronized int updateSystems(UnaryOperator<List<SystemEntry>> change) {
        Catalog c = snapshot().catalog();
        List<SystemEntry> next = change.apply(c.systems());
        if (next.equals(c.systems())) {
            return 0;
        }
        commit(new Catalog(next, c.flows()));
        return 1;
    }

    // ------------------------------------------------------------------ flows

    public synchronized Flow saveFlow(Flow f, String originalId, Long expectedVersion) {
        checkVersion(expectedVersion);
        Catalog c = snapshot().catalog();
        if (f.id() == null && f.name() != null) {
            f = new Flow(slug(f.name()), f.name(), f.description(), f.steps());
        }
        final Flow flow = f;
        boolean exists = c.flows().stream().anyMatch(x -> x.id().equals(flow.id()));
        if ((originalId == null || !originalId.equals(flow.id())) && exists) {
            throw new ConflictException("a flow with id '" + flow.id() + "' already exists");
        }
        List<Flow> flows = new ArrayList<>(c.flows().stream().filter(x -> !x.id().equals(originalId)).toList());
        int at = originalId == null ? -1 : indexOf(c.flows(), originalId);
        flows.add(at < 0 ? flows.size() : at, flow);
        commit(new Catalog(c.systems(), flows));
        return flow;
    }

    public synchronized void deleteFlow(String id, Long expectedVersion) {
        checkVersion(expectedVersion);
        Catalog c = snapshot().catalog();
        if (indexOf(c.flows(), id) < 0) {
            throw new IllegalArgumentException("no flow '" + id + "'");
        }
        commit(new Catalog(c.systems(), c.flows().stream().filter(x -> !x.id().equals(id)).toList()));
    }

    // ------------------------------------------------------------------ import / export

    public String exportYaml() {
        try {
            return YAML.writeValueAsString(snapshot().catalog());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code replace}: the imported catalog becomes the catalog. {@code merge}: imported systems and
     * flows are added, replacing those with the same id. Returns counts.
     */
    public synchronized Map<String, Object> importYaml(String yaml, boolean replace) {
        Catalog in;
        try {
            in = YAML.readValue(yaml, Catalog.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("not a valid catalog: " + e.getOriginalMessage());
        }
        Catalog c = snapshot().catalog();
        Catalog next;
        if (replace) {
            next = in;
        } else {
            Map<String, SystemEntry> sys = new LinkedHashMap<>();
            c.systems().forEach(s -> sys.put(s.id(), s));
            in.systems().forEach(s -> sys.put(s.id(), s));
            Map<String, Flow> fl = new LinkedHashMap<>();
            c.flows().forEach(f -> fl.put(f.id(), f));
            in.flows().forEach(f -> fl.put(f.id(), f));
            next = new Catalog(new ArrayList<>(sys.values()), new ArrayList<>(fl.values()));
        }
        commit(next);
        return Map.of("systems", in.systems().size(), "flows", in.flows().size(), "mode", replace ? "replace" : "merge");
    }

    // ------------------------------------------------------------------ validation

    /** Everything wrong with a catalog, empty when it is valid. */
    public List<String> problems(Catalog c) {
        List<String> p = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SystemEntry s : c.systems()) {
            String where = s.id() == null ? "a system" : s.id();
            if (s.id() == null || !Model.ID.matcher(s.id()).matches()) {
                p.add(where + ": id must be lowercase letters, digits and '-' (max 63), got '" + s.id() + "'");
            } else if (!ids.add(s.id())) {
                p.add(where + ": duplicate id");
            }
            if (s.name() == null) {
                p.add(where + ": name is required");
            }
            if (!kinds.known(s.kind())) {
                p.add(where + ": unknown kind '" + s.kind() + "' (see the kinds list, or use generic)");
            }
            if (s.category() != null && !kinds.knownCategory(s.category())) {
                p.add(where + ": unknown category '" + s.category() + "'");
            }
            for (Link l : s.links()) {
                if (!httpUrl(l.url())) {
                    p.add(where + ": link '" + (l.label() != null ? l.label() : l.url()) + "' must be an http(s) URL");
                }
                if (!Model.LINK_TYPES.contains(l.type())) {
                    p.add(where + ": link type must be one of " + Model.LINK_TYPES);
                }
            }
            if (s.healthUrl() != null && !httpUrl(s.healthUrl())) {
                p.add(where + ": health URL must be an http(s) URL");
            }
            if (s.access() != null && s.access().method() != null && !Model.ACCESS_METHODS.contains(s.access().method())) {
                p.add(where + ": access method must be one of " + Model.ACCESS_METHODS);
            }
            if (s.access() != null && looksLikeSecret(s.access().credentialRef())) {
                p.add(where + ": the credential reference looks like a password or token; store the secret in a password manager and point to it here");
            }
        }
        for (SystemEntry s : c.systems()) {
            for (Dependency d : s.dependsOn()) {
                if (!ids.contains(d.to())) {
                    p.add(s.id() + ": depends on unknown system '" + d.to() + "'");
                }
                if (d.to().equals(s.id())) {
                    p.add(s.id() + ": cannot depend on itself");
                }
            }
        }
        Set<String> flowIds = new HashSet<>();
        for (Flow f : c.flows()) {
            if (f.id() == null || !Model.ID.matcher(f.id()).matches()) {
                p.add("flow '" + f.name() + "': id must be lowercase letters, digits and '-'");
            } else if (!flowIds.add(f.id())) {
                p.add("flow " + f.id() + ": duplicate id");
            }
            if (f.name() == null) {
                p.add("flow " + f.id() + ": name is required");
            }
            for (FlowStep st : f.steps()) {
                for (String ref : new String[] {st.from(), st.to()}) {
                    if (ref != null && !ids.contains(ref)) {
                        p.add("flow " + f.id() + ": step refers to unknown system '" + ref + "'");
                    }
                }
                if (st.from() == null && st.to() == null) {
                    p.add("flow " + f.id() + ": every step needs a system");
                }
            }
        }
        return p;
    }

    private void commit(Catalog next) {
        List<String> p = problems(next);
        if (!p.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", p));
        }
        try {
            write(next);
            catalog = next;
            loadedMtime = Files.getLastModifiedTime(file);
            version++;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private void write(Catalog c) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        String header = "# Command Center catalog: systems, their links, access, dependencies, and flows.\n"
                + "# Edited from the UI; hand edits are picked up automatically. Never put passwords here.\n";
        Files.writeString(tmp, header + YAML.writeValueAsString(c));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void checkVersion(Long expected) {
        if (expected != null && expected != version) {
            throw new ConflictException("the catalog changed since you opened it; reload and try again");
        }
    }

    private static SystemEntry renameDeps(SystemEntry s, String from, String to) {
        return s.withDependsOn(s.dependsOn().stream().map(d -> d.to().equals(from) ? new Dependency(to, d.label()) : d).toList());
    }

    private static Flow renameSteps(Flow f, String from, String to) {
        return new Flow(f.id(), f.name(), f.description(), f.steps().stream().map(st -> new FlowStep(
                from.equals(st.from()) ? to : st.from(), from.equals(st.to()) ? to : st.to(), st.text())).toList());
    }

    private static int indexOf(List<Flow> flows, String id) {
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static boolean httpUrl(String u) {
        try {
            URI x = URI.create(u);
            return ("http".equals(x.getScheme()) || "https".equals(x.getScheme())) && x.getHost() != null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** A credential reference should name a vault entry, not contain a secret. */
    static boolean looksLikeSecret(String ref) {
        if (ref == null) {
            return false;
        }
        String r = ref.trim();
        if (r.matches("(?i).*(password|passwd|pwd|secret|token)\\s*[:=]\\s*\\S+.*")) {
            return true;
        }
        // one long word mixing letters and digits, no spaces: very likely a key
        return !r.contains(" ") && r.length() >= 20 && r.matches(".*\\d.*") && r.matches(".*[A-Za-z].*")
                && !r.contains("/") && !r.contains(":");
    }

    public static String slug(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            s = "system";
        }
        return s.length() > 63 ? s.substring(0, 63).replaceAll("-+$", "") : s;
    }
}
