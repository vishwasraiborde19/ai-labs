package com.ailabs.commandcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ailabs.commandcenter.config.CcProperties;

/** Who changed what in the catalog: {@code ~/.command-center/audit.log}, one line per change. */
public final class Audit {

    private static final Logger log = LoggerFactory.getLogger("audit");
    private static final ThreadLocal<String> ACTOR = ThreadLocal.withInitial(() -> "system");

    private Audit() {}

    public static void actor(String a) {
        ACTOR.set(a);
    }

    public static void clear() {
        ACTOR.remove();
    }

    public static synchronized void log(String action, String detail) {
        String d = detail == null ? "" : detail.replace('\n', ' ');
        if (d.length() > 1000) {
            d = d.substring(0, 1000) + "...";
        }
        String line = Instant.now() + " [" + ACTOR.get() + "] " + action + " " + d;
        log.info(line);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Cannot write audit log: {}", e.getMessage());
        }
    }

    public static Path file() {
        return CcProperties.home().resolve("audit.log");
    }

    /** Last {@code n} lines, newest last. */
    public static List<String> tail(int n) {
        try {
            Path f = file();
            if (!Files.exists(f)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
            return all.subList(Math.max(0, all.size() - n), all.size());
        } catch (IOException e) {
            return List.of("cannot read audit log: " + e.getMessage());
        }
    }
}
