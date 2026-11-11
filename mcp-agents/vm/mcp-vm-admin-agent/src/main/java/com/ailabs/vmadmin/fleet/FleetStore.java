package com.ailabs.vmadmin.fleet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.ailabs.vmadmin.config.AdminProperties;

/**
 * {@code fleet.yml}: the hosts the control centre manages. Edits from the UI are validated, written
 * atomically (mode 600, previous copy kept as {@code fleet.yml.bak}) and guarded by a version hash,
 * so two browser tabs cannot silently overwrite each other. Hand edits are picked up on the next read.
 */
@Component
public class FleetStore {

    private static final Logger log = LoggerFactory.getLogger(FleetStore.class);

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Fleet(List<Host> hosts) {
        public Fleet {
            hosts = hosts != null ? List.copyOf(hosts) : List.of();
        }
    }

    public record Snapshot(Fleet fleet, String version) {}

    public static class ConflictException extends RuntimeException {
        ConflictException() {
            super("fleet.yml changed since you loaded it; reload and try again");
        }
    }

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_DEFAULT)  // omit nulls, empty lists, false
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private Snapshot current;
    private long loadedStamp = Long.MIN_VALUE;

    public FleetStore() {
        this(AdminProperties.home().resolve("fleet.yml"));
    }

    public FleetStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized Snapshot snapshot() {
        long stamp = stamp();
        if (current == null || stamp != loadedStamp) {
            current = load();
            loadedStamp = stamp;
        }
        return current;
    }

    public Optional<Host> find(String name) {
        return snapshot().fleet().hosts().stream().filter(h -> h.name().equals(name)).findFirst();
    }

    public Host require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("No host named '" + name + "'"));
    }

    /** Add a host, or replace the one called {@code originalName} (which allows renaming). */
    public synchronized Snapshot upsert(Host host, String originalName, String expectedVersion) {
        Snapshot s = checkVersion(expectedVersion);
        List<String> problems = host.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        List<Host> hosts = new ArrayList<>(s.fleet().hosts());
        int at = originalName == null ? -1 : indexOf(hosts, originalName);
        if (originalName != null && at < 0) {
            throw new IllegalArgumentException("No host named '" + originalName + "'");
        }
        int clash = indexOf(hosts, host.name());
        if (clash >= 0 && clash != at) {
            throw new IllegalArgumentException("A host named '" + host.name() + "' already exists");
        }
        if (at >= 0) {
            hosts.set(at, host);
        } else {
            hosts.add(host);
        }
        return write(new Fleet(hosts));
    }

    public synchronized Snapshot remove(String name, String expectedVersion) {
        Snapshot s = checkVersion(expectedVersion);
        List<Host> hosts = new ArrayList<>(s.fleet().hosts());
        if (!hosts.removeIf(h -> h.name().equals(name))) {
            throw new IllegalArgumentException("No host named '" + name + "'");
        }
        return write(new Fleet(hosts));
    }

    private Snapshot checkVersion(String expected) {
        Snapshot s = snapshot();
        if (expected != null && !expected.equals(s.version())) {
            throw new ConflictException();
        }
        return s;
    }

    private static int indexOf(List<Host> hosts, String name) {
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private Snapshot load() {
        if (!Files.exists(file)) {
            return new Snapshot(new Fleet(List.of()), hash(""));
        }
        try {
            String text = Files.readString(file);
            Fleet f = text.isBlank() ? new Fleet(List.of()) : YAML.readValue(text, Fleet.class);
            for (Host h : f.hosts()) {
                if (!h.problems().isEmpty()) {
                    log.warn("fleet.yml: {}", String.join("; ", h.problems()));
                }
            }
            return new Snapshot(f, hash(text));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private Snapshot write(Fleet fleet) {
        try {
            String text = "# Managed by mcp-vm-admin-agent (UI). Hand edits are fine; keep tokens out of this file.\n"
                    + YAML.writeValueAsString(fleet);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            current = new Snapshot(fleet, hash(text));
            loadedStamp = stamp();
            return current;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private long stamp() {
        try {
            return Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
