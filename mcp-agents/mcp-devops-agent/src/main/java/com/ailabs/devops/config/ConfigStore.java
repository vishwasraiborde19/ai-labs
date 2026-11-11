package com.ailabs.devops.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * Owns {@code systems.yml}. The admin UI writes it; every agent process (stdio or HTTP) re-reads it
 * when it changes, so edits apply without a restart.
 *
 * <ul>
 *   <li>Writes are atomic (temp file + rename), mode 600, and keep the previous file as {@code .bak}.</li>
 *   <li>Each saved file has a version (sha-256 of its bytes). A save must name the version it was
 *       based on, so two editors cannot silently overwrite each other.</li>
 *   <li>A file that fails to parse or validate on reload is ignored and the last good config is kept.</li>
 *   <li>If the file does not exist yet, it is seeded from {@code devops.jenkins/dockerhub/kubernetes}
 *       in config.yml, so existing setups keep working.</li>
 * </ul>
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    private static final Logger audit = LoggerFactory.getLogger("audit");
    private static final long STAT_INTERVAL_MS = 1000;

    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Snapshot(Systems systems, String version, Path file) {}

    /** Thrown when a save is based on an outdated version. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Thrown when a save does not validate. */
    public static class InvalidConfigException extends RuntimeException {
        private final List<String> errors;

        public InvalidConfigException(List<String> errors) {
            super("Invalid systems config: " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    private final Path file;
    private volatile Snapshot current;
    private volatile long lastMtime = Long.MIN_VALUE;
    private volatile long lastSize = -1;
    private volatile long lastStat;
    private volatile long generation;

    @Autowired
    public ConfigStore(DevopsProperties props) {
        this(resolve(props.systemsFile()), new Systems(props.jenkins(), props.dockerhub(), props.kubernetes(), props.health()));
    }

    public ConfigStore(Path file, Systems seed) {
        this.file = file;
        if (!Files.exists(file) && seed != null && !isEmpty(seed)) {
            List<String> errors = seed.validate();
            if (!errors.isEmpty()) {
                throw new InvalidConfigException(errors);
            }
            write(seed);
            log.info("Created {} from the systems defined in config.yml; edit systems there or in the admin UI from now on", file);
        }
        reload(true);
    }

    public Path file() {
        return file;
    }

    public Systems current() {
        return snapshot().systems();
    }

    /** Increases every time a different config becomes active; used to rebuild clients. */
    public long generation() {
        snapshot();
        return generation;
    }

    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        if (now - lastStat > STAT_INTERVAL_MS) {
            lastStat = now;
            reload(false);
        }
        return current;
    }

    /**
     * Validate and write a new config.
     *
     * @param expectedVersion the version the editor started from ({@code none} if no file existed)
     */
    public synchronized Snapshot save(Systems systems, String expectedVersion, String who) {
        reload(false);
        if (expectedVersion == null || !expectedVersion.equals(current.version())) {
            throw new ConflictException("systems.yml changed since it was loaded (now " + shortV(current.version())
                    + "); reload and apply your edits again");
        }
        List<String> errors = systems.validate();
        if (!errors.isEmpty()) {
            throw new InvalidConfigException(errors);
        }
        write(systems);
        reload(true);
        audit.info("ADMIN {} saved systems.yml version={} jenkins={} dockerhub={} kubernetes={}", who, shortV(current.version()),
                systems.jenkins().size(), systems.dockerhub().size(), systems.kubernetes().size());
        return current;
    }

    public String toYaml(Systems systems) {
        try {
            return YAML.writeValueAsString(systems);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ internals

    private synchronized void reload(boolean strict) {
        try {
            if (!Files.exists(file)) {
                if (current == null || !"none".equals(current.version())) {
                    current = new Snapshot(Systems.empty(), "none", file);
                    generation++;
                }
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            if (!strict && mtime == lastMtime && size == lastSize) {
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            String version = sha256(bytes);
            lastMtime = mtime;
            lastSize = size;
            if (current != null && version.equals(current.version())) {
                return;
            }
            Systems s = bytes.length == 0 ? Systems.empty() : YAML.readValue(bytes, Systems.class);
            List<String> errors = s.validate();
            if (!errors.isEmpty()) {
                throw new InvalidConfigException(errors);
            }
            current = new Snapshot(s, version, file);
            generation++;
            log.info("Loaded {} (version {}): {} jenkins, {} dockerhub, {} kubernetes", file, shortV(version),
                    s.jenkins().size(), s.dockerhub().size(), s.kubernetes().size());
        } catch (IOException | RuntimeException e) {
            if (current == null) {
                throw new IllegalStateException("Cannot load " + file + ": " + e.getMessage(), e);
            }
            log.error("Ignoring invalid {} and keeping version {}: {}", file, shortV(current.version()), e.getMessage());
        }
    }

    private void write(Systems s) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            String yaml = "# Managed by the mcp-devops-agent admin UI. Secrets are references (access:, vault:, file:, env:, path:).\n"
                    + toYaml(s);
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".systems", ".tmp");
            try {
                Files.writeString(tmp, yaml);
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX file system
                }
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isEmpty(Systems s) {
        return s.jenkins().isEmpty() && s.dockerhub().isEmpty() && s.kubernetes().isEmpty() && s.health().allowedHosts().isEmpty();
    }

    static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return DevopsProperties.home().resolve("systems.yml");
        }
        return Path.of(configured.startsWith("~/") ? System.getProperty("user.home") + configured.substring(1) : configured);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortV(String v) {
        return v.length() > 12 ? v.substring(0, 12) : v;
    }
}
