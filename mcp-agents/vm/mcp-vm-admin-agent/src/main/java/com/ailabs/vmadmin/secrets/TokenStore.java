package com.ailabs.vmadmin.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.config.AdminProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * {@code tokens.yml} (mode 600): agent tokens pasted into the UI, keyed by host name. Referenced from
 * fleet.yml as {@code store:<key>}. For production, prefer {@code vault:} references.
 */
@Component
public class TokenStore {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path file;

    public TokenStore() {
        this(AdminProperties.home().resolve("tokens.yml"));
    }

    public TokenStore(Path file) {
        this.file = file;
    }

    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(read().get(key));
    }

    public synchronized void put(String key, String token) {
        if (token == null || token.isBlank() || token.length() > 512 || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("token must be 1-512 printable characters without spaces");
        }
        Map<String, String> m = read();
        m.put(key, token.trim());
        write(m);
    }

    public synchronized void remove(String key) {
        Map<String, String> m = read();
        if (m.remove(key) != null) {
            write(m);
        }
    }

    private Map<String, String> read() {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            Map<String, String> m = YAML.readValue(file.toFile(), new TypeReference<TreeMap<String, String>>() {});
            return m != null ? m : new TreeMap<>();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private void write(Map<String, String> m) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            Files.writeString(tmp, "# Agent tokens pasted into the vm-admin UI. Keep this file private (mode 600).\n"
                    + YAML.writeValueAsString(m), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
