package com.ailabs.vmadmin.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Run-time state that should survive a restart (last known host status, active alerts, schedule run
 * history), kept as JSON under {@code $VMADMIN_HOME/state/}. Configuration (fleet.yml, tokens.yml,
 * schedules.yml) lives elsewhere; losing these files only loses history, never hosts.
 *
 * <p>Writes are atomic (temp file + move, mode 600). A missing or unreadable file reads as {@code null};
 * an unreadable one is moved aside as {@code <name>.corrupt} so the next write starts clean.
 */
public final class StateFile<T> {

    private static final Logger log = LoggerFactory.getLogger(StateFile.class);
    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private final TypeReference<T> type;

    public StateFile(Path file, TypeReference<T> type) {
        this.file = file;
        this.type = type;
    }

    /** {@code state/<name>} next to the given config file, e.g. next to fleet.yml. */
    public static <T> StateFile<T> beside(Path configFile, String name, TypeReference<T> type) {
        Path dir = configFile.toAbsolutePath().getParent();
        return new StateFile<>(dir.resolve("state").resolve(name), type);
    }

    public Path file() {
        return file;
    }

    public synchronized T read() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable state file {}: {}", file, e.getMessage());
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
            return null;
        }
    }

    public synchronized void write(T value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // State is a convenience; never fail the operation that produced it.
            log.warn("Cannot write state file {}: {}", file, e.getMessage());
        }
    }
}
